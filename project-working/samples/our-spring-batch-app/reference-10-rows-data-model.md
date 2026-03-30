# Walkthrough: real file with 10 rows → data model

**Sample files** (same folder): `lending-10rows.psv` (all valid), `lending-10rows-mixed-errors.psv` (7 valid / 3 invalid).  
**Format:** 10 physical lines, pipe-separated — `loan_id|amount|currency` (no header row; line number = record number 1–10).

**Assumptions (same as `understanding`):**

- Feed **`FEED-LENDING-01`**, source **`SRC-PARTNER-01`**.
- Chunk size **5** lines → **2 chunks** (lines 1–5 and 6–10).
- **§1–§10 (main walkthrough):** file **`lending-10rows-mixed-errors.psv`** — **7** `VALID` / **3** `INVALID` rows (lines 3, 8, 9); **`DATA_PERSISTENCE`** holds **7** rows only.
- **All-valid shortcut:** `lending-10rows.psv` — ten `VALID` rows; see **§6.2** and **10** `DATA_PERSISTENCE` rows.
- **Scenario B (§13):** same row-level pattern with **alternate run ids** (`DCE-61001`, `WFEX-21001`, `REC-B*`) — duplicate illustration for a second run.

**Schema context:** Table names below align with the **`E2E_CONNECT_DATA`** area of this ingest model (see **`DATA-MODEL-lending-ingest.md`**). Exact columns require your DDL.

---

## 0. Full table list — role in this walkthrough (happy path + errors)

| Table(s) | In happy-path sections below? | Role |
|----------|---------------------------------|------|
| `SOURCE_REGISTRY`, `SOURCE_SCHEDULE` | §2 | Catalog — who sends, when expected (**sample rows populated**). |
| `FEEDS`, `FIELDS`, `FEED_FIELD_ASSOCIATION` | §2 | Feed + column layout (**sample rows populated**). |
| `FEEDS_AUDIT`, `FIELDS_AUDIT`, `FEED_FIELD_ASSOCIATION_AUDIT` | — | **Config change history**, not file row errors. |
| `FEEDS_TEMP`, `FIELDS_TEMP`, `FEED_FIELD_ASSOCIATION_TEMP` | — | **Draft metadata** before promotion. |
| `LIST_OF_VALUES`, `LIST_OF_VALUES_AUDIT` | §2 / §7 | Allowed values (**§2 sample**); audit = config history. |
| `VALIDATION_RULES`, `VALIDATION_RULES_AUDIT`, `VALIDATION_RULES_TEMP` | §2 / §7 | Rules (**§2 sample**); audit/temp = rule lifecycle. |
| `EXTERNAL_DATASOURCE_CONFIG` | §2 | Bucket/prefix (**sample row in §2**). |
| `FRAMEWORK_CONFIGURATION`, `FRAMEWORK_CONFIGURATION_TEMP`, `FRAMEWORK_CONFIG_HOST`, `FRAMEWORK_CONFIG_HOST_TEMP` | §2 | Chunk size, hosts, env (**§2: `chunk.max_lines = 5`**; optional host). |
| `FRAMEWORK_CONF_AUDIT` | — | Config audit trail. |
| `FRAMEWORK_WORKFLOW`, `FRAMEWORK_STAGE`, `WORKFLOW_STAGE_ASSC` | §4 | Workflow **definitions**. |
| `FRAMEWORK_WORKFLOW_AUDIT`, `FRAMEWORK_STAGE_AUDIT`, `WORKFLOW_STAGE_ASSC_AUDIT` | — | **Definition** change history. |
| `FRAMEWORK_WORKFLOW_TEMP`, `FRAMEWORK_STAGE_TEMP`, `WORKFLOW_STAGE_ASSC_TEMP` | — | Draft workflow definitions. |
| `FRAMEWORK_STAGE_TEST` | — | Test harness for stages (not each file run). |
| `FRAMEWORK_WORKFLOW_EXECUTION` | §4 | **This run** of the workflow. |
| `FRAMEWORK_STAGE_EXECUTION` | §4 | **This run** of each stage. |
| `DATA_CONNECT_LOAD_EVENT` | §3 | **This file** instance; file-level status. |
| `JOB_CHUNK_EXECUTION_STATUS`, `JOB_CHUNK_WORKFLOW_EXEC_STATUS` | §5 | Chunk progress; can show **FAILED** if chunk fails. |
| `RECORD_EXECUTION_DATA` | §6 | **Per line:** `VALID` / `INVALID`, **`error_code`**, **`failed_rule_id`**, **`reason_or_error_message`** (human-readable), optional **raw_line** — **primary row-level error/reject tracking** in this schema. |
| `DATA_PERSISTENCE` | §8 | **Good rows only** (accepted after validation). |
| `TEMP_OUT` | §9 | Handoff of **accepted** rows downstream — **not** the main reject store. |
| `FRAMEWORK_AUDIT_LOG` | §12 / **§13** | **Runtime** audit/log events (errors, warnings, framework messages) — **strong candidate** for “where errors are logged” alongside row-level tables. |
| `FRAME_WORK_TEMP` | — | Typo/naming variant of framework temp — confirm in DDL. |

**Important:** This catalog does **not** include a table literally named `REJECT` / `BAD_RECORDS`. In practice, **bad data** is usually represented by: **`RECORD_EXECUTION_DATA`** (status + error fields + optional raw line), optionally **`FRAMEWORK_AUDIT_LOG`**, and **no row** in **`DATA_PERSISTENCE`**. Confirm with your `CREATE TABLE` for `RECORD_EXECUTION_DATA` and `FRAMEWORK_AUDIT_LOG`.

---

## 1. What sits in S3 (not in DB)

| Object | Value |
|--------|--------|
| Bucket / key | `s3://ingest-bucket/input/lending-10rows-mixed-errors.psv` |
| Lines | 10 (each = one business record; **3** lines fail validation — see §6) |

---

## 2. Chapter 0 — Already in DB (metadata)

Illustrative rows aligned with this walkthrough (`FEED-LENDING-01`, `SRC-PARTNER-01`, chunk size **5** → two chunks for 10 lines). Column names are typical; adjust to your DDL.

**No per-row file data** here — only definitions loaded **before** the ingest file (e.g. `lending-10rows-mixed-errors.psv`) is processed.

---

### `SOURCE_REGISTRY`

| src_id | src_name | status |
|--------|----------|--------|
| SRC-PARTNER-01 | Synthetic Example Source | ACTIVE |

---

### `SOURCE_SCHEDULE`

| schedule_id | src_id | cron_or_desc |
|-------------|--------|----------------|
| SCH-DAILY-01 | SRC-PARTNER-01 | Daily 02:00 UTC |

---

### `FEEDS`

| feed_id | feed_name | src_id | schedule_id | file_pattern | delimiter |
|---------|-----------|--------|---------------|--------------|-----------|
| FEED-LENDING-01 | lending | SRC-PARTNER-01 | SCH-DAILY-01 | lending*.psv | \| |

*Objects such as `input/lending-10rows.psv` or `input/lending-10rows-mixed-errors.psv` match `lending*.psv`.*

---

### `FIELDS` (global catalog; fields reusable across feeds)

| field_id | field_name | data_type | max_len |
|----------|------------|-----------|---------|
| FLD-LOAN-ID | loan_id | STRING | 32 |
| FLD-AMT | amount | DECIMAL | 18 |
| FLD-CCY | currency | STRING | 3 |

---

### `FEED_FIELD_ASSOCIATION` (column order in PSV)

| feed_id | field_id | field_sequence | business_key |
|---------|----------|----------------|--------------|
| FEED-LENDING-01 | FLD-LOAN-ID | 1 | Y |
| FEED-LENDING-01 | FLD-AMT | 2 | N |
| FEED-LENDING-01 | FLD-CCY | 3 | N |

---

### `LIST_OF_VALUES`

| lov_id | name | values |
|--------|------|--------|
| LOV-CCY-01 | ISO_CURRENCY | USD,EUR,GBP |

*(Covers currencies used in the sample lending PSV files.)*

---

### `VALIDATION_RULES`

| ID | NAME | DESCR | EXPRESSION | SOURCE |
|----|------|-------|------------|--------|
| VR-LOAN-ID | loan_id_present | Loan id required, non-blank | notBlank(field(loan_id)) | ENGINE |
| VR-LOAN-FMT | loan_id_format | Loan id matches L-####### pattern | matches(field(loan_id), '^L-\\d{4}$') | ENGINE |
| VR-AMT | amount_numeric | Amount must parse as decimal | matches(field(amount), DECIMAL) | ENGINE |
| VR-AMT-GT0 | amount_positive | Amount strictly greater than zero | gt(field(amount), 0) | ENGINE |
| VR-CCY | currency_lov | Currency in approved list | in(field(currency), LOV-CCY-01) | ENGINE |

*(Same rows repeated in §7 with runtime notes.)*

---

### `EXTERNAL_DATASOURCE_CONFIG`

| config_id | bucket_or_root | prefix |
|-----------|----------------|--------|
| EXT-INGEST-01 | ingest-bucket | input/ |

*Resolves to `s3://ingest-bucket/input/lending-10rows-mixed-errors.psv` for the §1–§10 run.*

---

### `FRAMEWORK_CONFIGURATION`

| config_key | config_value |
|------------|----------------|
| chunk.max_lines | 5 |

*Ten lines → chunks `CHK-201` (lines 1–5) and `CHK-202` (lines 6–10).*

---

### Optional: `FRAMEWORK_CONFIG_HOST` (if your schema ties env to host)

| host_id | env_name | notes |
|---------|----------|--------|
| FCH-01 | BATCH-ECS-01 | Illustrative processing host for framework jobs |

*Omit if not used in your build.*

---

## 3. Chapter 2 — File registered: `DATA_CONNECT_LOAD_EVENT`

One row for **this** drop:

| load_event_id | feed_id | s3_key | status | notes |
|---------------|---------|--------|--------|--------|
| `DCE-60001` | `FEED-LENDING-01` | `input/lending-10rows-mixed-errors.psv` | `RECEIVED` → `LOADING` → **`COMPLETED_WITH_ERRORS`** (after run) | Ties to `etag`, size, `src_id`; terminal status reflects partial rejects |

**`wf_exec_id` on this table (if present):** usually **NULL** on first insert, then **UPDATE** after step 4 — or **omit column** and use **`FRAMEWORK_WORKFLOW_EXECUTION.load_event_id`** only.

---

## 4. Chapter 3 — One run: `FRAMEWORK_WORKFLOW_EXECUTION`

| wf_exec_id | workflow_id | load_event_id | status |
|------------|-------------|----------------|--------|
| `WFEX-20001` | `WF-INGEST-01` | `DCE-60001` | `RUNNING` → **`COMPLETED_WITH_ERRORS`** (7 saved, 3 rejected) |

**`FRAMEWORK_STAGE_EXECUTION`** — one row per stage instance for this run:

| stage_exec_id | wf_exec_id | stage_id | status |
|---------------|------------|----------|--------|
| `STGEX-201` | `WFEX-20001` | `STG-PARSE` | `COMPLETED` |
| `STGEX-202` | `WFEX-20001` | `STG-VALID` | `COMPLETED` |
| `STGEX-203` | `WFEX-20001` | `STG-PERSIST` | `COMPLETED` |

**Trace:** `WFEX-20001` → **`DCE-60001`** (recommended FK direction); stages **`STGEX-201` → `STGEX-202` → `STGEX-203`** in order parse → validate → persist.

---

## 5. Chapter 4 — Two chunks: `JOB_CHUNK_*`

With **chunk size = 5**:

| chunk_id | wf_exec_id | line_from | line_to | status |
|----------|------------|-----------|---------|--------|
| `CHK-201` | `WFEX-20001` | 1 | 5 | `DONE` |
| `CHK-202` | `WFEX-20001` | 6 | 10 | `DONE` |

`JOB_CHUNK_WORKFLOW_EXEC_STATUS`: each chunk × each stage → `DONE` (as in the full `understanding` doc).

---

## 6. Chapter 5 — Ten rows: `RECORD_EXECUTION_DATA`

One row **per physical line** (10 rows). Source file: **`lending-10rows-mixed-errors.psv`**.

**Recommended columns for auditing:** besides `record_status`, store **`error_code`** (machine), **`failed_rule_id`** (FK to `VALIDATION_RULES.ID`), and **`reason_or_error_message`** (human-readable text for ops, email, and support). Your DDL might name the last column `error_message`, `reject_reason`, or `validation_message` — same idea.

### 6.1 Mixed valid / invalid (primary example — §1–§10)

| rec_id | wf_exec_id | line_no | chunk_id | loan_id | record_status | error_code | failed_rule_id | reason_or_error_message |
|--------|------------|---------|----------|---------|---------------|------------|----------------|-------------------------|
| `REC-501` | `WFEX-20001` | 1 | `CHK-201` | L-3001 | `VALID` | — | — | — |
| `REC-502` | `WFEX-20001` | 2 | `CHK-201` | L-3002 | `VALID` | — | — | — |
| `REC-503` | `WFEX-20001` | 3 | `CHK-201` | L-3003 | `INVALID` | `VAL_NUMERIC` | `VR-AMT` | Amount field cannot be parsed as a decimal number (value: not-a-number). |
| `REC-504` | `WFEX-20001` | 4 | `CHK-201` | L-3004 | `VALID` | — | — | — |
| `REC-505` | `WFEX-20001` | 5 | `CHK-201` | L-3005 | `VALID` | — | — | — |
| `REC-506` | `WFEX-20001` | 6 | `CHK-202` | L-3006 | `VALID` | — | — | — |
| `REC-507` | `WFEX-20001` | 7 | `CHK-202` | L-3007 | `VALID` | — | — | — |
| `REC-508` | `WFEX-20001` | 8 | `CHK-202` | L-3008 | `INVALID` | `VAL_LOV` | `VR-CCY` | Currency code JPY is not in the allowed list (USD, EUR, GBP). |
| `REC-509` | `WFEX-20001` | 9 | `CHK-202` | L-3009 | `INVALID` | `VAL_RANGE` | `VR-AMT-GT0` | Amount must be strictly greater than zero. |
| `REC-510` | `WFEX-20001` | 10 | `CHK-202` | L-3010 | `VALID` | — | — | — |

**Trace:** all **`WFEX-20001`**; lines 1–5 → **`CHK-201`**, lines 6–10 → **`CHK-202`**. **Invalid** lines still get a **`RECORD_EXECUTION_DATA`** row — they are **not** omitted (unless your product skips unparseable lines entirely).

### 6.2 All-valid variant (file `lending-10rows.psv`)

If every line passes: all **`record_status` = `VALID`**, and **`error_code` / `failed_rule_id` / `reason_or_error_message`** are null or `—` for every row. Then **`DATA_PERSISTENCE`** contains **10** rows (one per line). This walkthrough’s **§8** follows the **mixed** case (**7** persisted rows).

---

## 7. Chapter 6 — Rules (`VALIDATION_RULES` / `LIST_OF_VALUES`)

**What “no new inserts during the run” means:** While processing the ingest file (e.g. `lending-10rows-mixed-errors.psv`), the job **does not add rows** to `VALIDATION_RULES`. The rows below are **pre-seeded** (same logical content as §2). At runtime the engine **SELECT**s these rules and evaluates them **per line** — it does **not** `INSERT` into `VALIDATION_RULES` for each line or each failure.

*(Oracle-style rule metadata UIs often label columns `ID`, `DESCR`, `NAME`, `EXPRESSION`, `SOURCE` — map `rule_id` → `ID`, `descr` → `DESCR`, etc.)*

### `LIST_OF_VALUES` — sample data

| lov_id | name | values |
|--------|------|--------|
| LOV-CCY-01 | ISO_CURRENCY | USD,EUR,GBP |

---

### `VALIDATION_RULES` — sample data (metadata; not mutated during ingest)

| ID | NAME | DESCR | EXPRESSION | SOURCE |
|----|------|-------|------------|--------|
| VR-LOAN-ID | loan_id_present | Loan id required, non-blank | notBlank(field(loan_id)) | ENGINE |
| VR-LOAN-FMT | loan_id_format | Loan id matches L-####### pattern | matches(field(loan_id), '^L-\\d{4}$') | ENGINE |
| VR-AMT | amount_numeric | Amount must parse as decimal | matches(field(amount), DECIMAL) | ENGINE |
| VR-AMT-GT0 | amount_positive | Amount strictly greater than zero | gt(field(amount), 0) | ENGINE |
| VR-CCY | currency_lov | Currency in approved list | in(field(currency), LOV-CCY-01) | ENGINE |

---

### Runtime (not stored in `VALIDATION_RULES`) — example evaluation for **line 1** (`L-3001|125000.00|USD`)

| Rule ID | Result | Notes |
|---------|--------|--------|
| VR-LOAN-ID | PASS | `L-3001` present |
| VR-LOAN-FMT | PASS | matches pattern (adjust regex if your real ids differ) |
| VR-AMT | PASS | numeric |
| VR-AMT-GT0 | PASS | 125000.00 > 0 |
| VR-CCY | PASS | USD ∈ LOV |

Same checks repeat for each line; **outcomes** land in **`RECORD_EXECUTION_DATA`** (`VALID` / `INVALID` + **`reason_or_error_message`**), not as new rows in **`VALIDATION_RULES`**.

---

## 8. Chapter 7 — Persisted rows: `DATA_PERSISTENCE`

One row per **`VALID`** record only. For **`lending-10rows-mixed-errors.psv`**, **7** rows (lines **3, 8, 9** have no persistence row).

| persistence_id | load_event_id | wf_exec_id | loan_id | amount | currency | rec_id |
|----------------|----------------|------------|---------|--------|----------|--------|
| `DP-601` | `DCE-60001` | `WFEX-20001` | L-3001 | 125000.00 | USD | `REC-501` |
| `DP-602` | `DCE-60001` | `WFEX-20001` | L-3002 | 98000.50 | USD | `REC-502` |
| `DP-603` | `DCE-60001` | `WFEX-20001` | L-3004 | 77000.00 | USD | `REC-504` |
| `DP-604` | `DCE-60001` | `WFEX-20001` | L-3005 | 210000.00 | USD | `REC-505` |
| `DP-605` | `DCE-60001` | `WFEX-20001` | L-3006 | 333000.75 | USD | `REC-506` |
| `DP-606` | `DCE-60001` | `WFEX-20001` | L-3007 | 15000.00 | GBP | `REC-507` |
| `DP-607` | `DCE-60001` | `WFEX-20001` | L-3010 | 50000.00 | USD | `REC-510` |

*No `DP-*` for `L-3003`, `L-3008`, `L-3009` — those loans failed validation (**§6.1**).*

---

## 9. Chapter 8 — Optional `TEMP_OUT`

Up to **7** rows pointing at `DP-601`–`DP-607` for downstream export (only **accepted** rows).

---

## 10. End-to-end ID chain (10-line file, 7 persisted)

```text
DCE-60001 (file — COMPLETED_WITH_ERRORS)
    ← referenced by — WFEX-20001 (workflow execution)
            ├── STGEX-201 / STGEX-202 / STGEX-203 (parse → validate → persist)
            ├── CHK-201 (lines 1–5), CHK-202 (lines 6–10)
            ├── REC-501 … REC-510 (10 rows: 7 VALID, 3 INVALID + reason_or_error_message)
            └── DP-601 … DP-607 (7 rows — only VALID lines)
```

---

## 11. Other invalid-line examples (summary)

**§6.1** already shows **three** invalid lines (3, 8, 9) with **`error_code`**, **`failed_rule_id`**, and **`reason_or_error_message`**. For a single bad line elsewhere (e.g. line 7 bad amount):

- **`RECORD_EXECUTION_DATA`:** that line → `INVALID` + codes + reason — see **§12.1**.
- **`DATA_PERSISTENCE`:** no row for that loan. **Chunks** usually stay **`DONE`** unless policy fails the whole chunk.

Full table mapping for errors: **§12**.

---

## 12. Where error data goes (schema overview)

There is **no separate table named `ERROR` or `REJECT`** in this model. Error/reject information is usually **distributed** by **layer**:

### 12.1 Row-level (most important for “bad lines”)

**`RECORD_EXECUTION_DATA`** — one row per physical line; for failures, **status** = `INVALID` (or equivalent) **instead of** inserting into **`DATA_PERSISTENCE`**.

Illustrative columns (names vary by DDL):

| Column idea | Example for failed line 3 (**§6.1**) |
|---------------|---------------------------|
| `rec_id` | `REC-503` |
| `wf_exec_id` | `WFEX-20001` |
| `line_no` | `3` |
| `chunk_id` | `CHK-201` |
| `loan_id` | `L-3003` (if parseable) or null |
| `record_status` | `INVALID` |
| `error_code` | `VAL_NUMERIC` |
| `failed_rule_id` | `VR-AMT` |
| `reason_or_error_message` | Human-readable text (shown in **§6.1** for lines 3, 8, 9) |
| `raw_line` / `payload` | Optional duplicate of bad line for forensics |

**`DATA_PERSISTENCE`** — **no row** for that loan/line when validation failed.

### 12.2 Framework / runtime log (errors, warnings, trace)

**`FRAMEWORK_AUDIT_LOG`** — use this for **framework-level** messages: stage failures, validation engine exceptions, correlation to **`wf_exec_id`** / **`load_event_id`**. Exact columns need your DDL; your schema may also store **`stage_exec_id`**, **`chunk_id`**, **`exception_class`**, **`created_at`**, **`thread`**, etc.

**Typical pattern (illustrative — same run `WFEX-20001` / `DCE-60001` unless noted):**  
*Aligned with **§6.1** — **three** invalid rows (lines **3, 8, 9**). For an **all-valid** file (`lending-10rows.psv`, **§6.2**), omit **`ROW_VALIDATION`** / **`THRESHOLD`** rows and use **`RUN_COMPLETED`** with **`valid=10, invalid=0`**.*

| log_id | wf_exec_id | load_event_id | stage_exec_id (opt) | chunk_id (opt) | severity | event_type | message | rec_id |
|--------|------------|---------------|------------------------|----------------|----------|------------|---------|--------|
| `LOG-9000` | `WFEX-20001` | `DCE-60001` | — | — | `INFO` | `RUN_STARTED` | Workflow execution started; feed=FEED-LENDING-01 | — |
| `LOG-9001` | `WFEX-20001` | `DCE-60001` | `STGEX-201` | — | `INFO` | `STAGE_STARTED` | Stage PARSE started | — |
| `LOG-9002` | `WFEX-20001` | `DCE-60001` | `STGEX-201` | — | `INFO` | `STAGE_COMPLETED` | Stage PARSE completed; lines read=10 | — |
| `LOG-9003` | `WFEX-20001` | `DCE-60001` | `STGEX-202` | — | `INFO` | `STAGE_STARTED` | Stage VALIDATE started | — |
| `LOG-9004` | `WFEX-20001` | `DCE-60001` | `STGEX-202` | `CHK-201` | `DEBUG` | `CHUNK_PROGRESS` | Validating chunk lines 1–5 | — |
| `LOG-9005` | `WFEX-20001` | `DCE-60001` | `STGEX-202` | `CHK-202` | `DEBUG` | `CHUNK_PROGRESS` | Validating chunk lines 6–10 | — |
| `LOG-9006` | `WFEX-20001` | `DCE-60001` | `STGEX-202` | — | `ERROR` | `ROW_VALIDATION` | Line 3: amount not numeric (rule VR-AMT) | `REC-503` |
| `LOG-9006b` | `WFEX-20001` | `DCE-60001` | `STGEX-202` | — | `ERROR` | `ROW_VALIDATION` | Line 8: currency JPY not in LOV (rule VR-CCY) | `REC-508` |
| `LOG-9006c` | `WFEX-20001` | `DCE-60001` | `STGEX-202` | — | `ERROR` | `ROW_VALIDATION` | Line 9: amount must be &gt; 0 (rule VR-AMT-GT0) | `REC-509` |
| `LOG-9007` | `WFEX-20001` | `DCE-60001` | `STGEX-202` | — | `WARN` | `THRESHOLD` | Reject count 3 below configured max_errors; continuing | — |
| `LOG-9008` | `WFEX-20001` | `DCE-60001` | `STGEX-202` | — | `INFO` | `STAGE_COMPLETED` | Stage VALIDATE completed; valid=7, invalid=3 | — |
| `LOG-9009` | `WFEX-20001` | `DCE-60001` | `STGEX-203` | — | `INFO` | `STAGE_STARTED` | Stage PERSIST started | — |
| `LOG-9010` | `WFEX-20001` | `DCE-60001` | `STGEX-203` | — | `ERROR` | `PERSIST_BATCH` | (Example) Batch insert retry 1/3 — transient DB timeout | — |
| `LOG-9011` | `WFEX-20001` | `DCE-60001` | `STGEX-203` | — | `INFO` | `STAGE_COMPLETED` | Stage PERSIST completed; rows written=7 | — |
| `LOG-9012` | `WFEX-20001` | `DCE-60001` | — | — | `INFO` | `RUN_COMPLETED` | Workflow execution completed with status COMPLETED_WITH_ERRORS (use plain COMPLETED if all rows valid) | — |
| `LOG-9013` | `WFEX-20001` | `DCE-60001` | `STGEX-202` | — | `ERROR` | `VALIDATION_ENGINE` | (Rare) Rule evaluation failed: expression parse error for rule VR-XYZ — see stack in app logs | — |

**Narrower example** (only row-level — if your product logs lightly):

| log_id | wf_exec_id | load_event_id | severity | event_type | message | rec_id |
|--------|------------|---------------|----------|------------|---------|--------|
| `LOG-9101` | `WFEX-20001` | `DCE-60001` | `ERROR` | `ROW_VALIDATION` | Line 3: amount not numeric | `REC-503` |
| `LOG-9102` | `WFEX-20001` | `DCE-60001` | `INFO` | `ROW_SKIPPED` | Invalid row not persisted | `REC-503` |

*If `FRAMEWORK_AUDIT_LOG` is empty in your DB, the product might log only to `RECORD_EXECUTION_DATA` or to files — confirm usage.*

### 12.3 File-level

**`DATA_CONNECT_LOAD_EVENT`** — **`status`** may move to `FAILED` only if the **entire load** fails (unreadable file, fatal error). For **partial success** (some bad rows), many systems keep **`COMPLETED_WITH_ERRORS`** / **`PARTIAL`** here while **`RECORD_EXECUTION_DATA`** carries row detail.

### 12.4 Run / stage / chunk level

| Table | When it shows “error” |
|-------|------------------------|
| **`FRAMEWORK_WORKFLOW_EXECUTION`** | `status` = `FAILED` if the run aborts. |
| **`FRAMEWORK_STAGE_EXECUTION`** | e.g. `STG-PERSIST` = `FAILED` if persist step blows up. |
| **`JOB_CHUNK_EXECUTION_STATUS`** | `FAILED` if the chunk is rolled back or aborted per policy. |

These describe **processing** health, not the **payload** of bad rows (payload lives in **`RECORD_EXECUTION_DATA`** / log).

### 12.5 What is *not* the “bad data” table

| Pattern | Why |
|---------|-----|
| **`FEEDS_AUDIT`, `FRAMEWORK_STAGE_AUDIT`, `VALIDATION_RULES_AUDIT`, …** | Almost always **who changed which config** and when — **not** rejected file rows. |
| **`FEEDS_TEMP`, `FRAMEWORK_WORKFLOW_TEMP`, …** | **Draft** definitions, not runtime rejects. |
| **`TEMP_OUT`** | Staging for **accepted** handoff — not the primary reject store. |

### 12.6 End-to-end error chain (same 10-line file as **§6.1** — lines 3, 8, 9 invalid)

```text
DCE-60001 (load event — COMPLETED_WITH_ERRORS)
    ← WFEX-20001
            ├── RECORD_EXECUTION_DATA: REC-503, REC-508, REC-509 = INVALID (+ reason_or_error_message)
            ├── FRAMEWORK_AUDIT_LOG: LOG-9006 … (optional, if enabled)
            ├── DATA_PERSISTENCE: 7 rows (no DP for L-3003, L-3008, L-3009)
            └── JOB_CHUNK_* / FRAMEWORK_STAGE_* : usually DONE (line-level rejects)
```

---

## 13. Scenario B — Error-in-data (mixed failures, full table samples)

Same **§2 metadata** (`VALIDATION_RULES`, `LIST_OF_VALUES`, etc.). **Same row-level pattern as §6.1** (7 valid / 3 invalid), using **different** **`load_event_id`**, **`wf_exec_id`**, and **`rec_id`** prefixes (`DCE-61001`, `WFEX-21001`, `REC-B*`) so the two runs do not collide in examples.

### 13.1 File in S3

| Object | Value |
|--------|--------|
| Sample file | `samples/lending-10rows-mixed-errors.psv` |
| S3 key | `input/lending-10rows-mixed-errors.psv` |

**Contents (10 lines)** — see `lending-10rows-mixed-errors.psv` on disk.

| line | Failure (if any) |
|------|------------------|
| 1 | OK |
| 2 | OK |
| 3 | **INVALID** — amount not numeric (`VR-AMT`) |
| 4 | OK |
| 5 | OK |
| 6 | OK |
| 7 | OK |
| 8 | **INVALID** — `JPY` not in `LOV-CCY-01` (`VR-CCY`) |
| 9 | **INVALID** — amount not &gt; 0 (`VR-AMT-GT0`) |
| 10 | OK |

**Counts:** 7 **VALID**, 3 **INVALID** → 7 rows in **`DATA_PERSISTENCE`**, 10 rows in **`RECORD_EXECUTION_DATA`**.

---

### 13.2 `DATA_CONNECT_LOAD_EVENT` (file-level partial success)

| load_event_id | feed_id | s3_key | status | summary (optional column) |
|---------------|---------|--------|--------|---------------------------|
| `DCE-61001` | `FEED-LENDING-01` | `input/lending-10rows-mixed-errors.psv` | `COMPLETED_WITH_ERRORS` | `accepted=7`, `rejected=3` |

*If your schema only has `FAILED` vs `COMPLETED`, map partial success to **`COMPLETED`** + derive counts from **`RECORD_EXECUTION_DATA`**.*

---

### 13.3 `FRAMEWORK_WORKFLOW_EXECUTION` (run still completes)

| wf_exec_id | workflow_id | load_event_id | status |
|------------|-------------|----------------|--------|
| `WFEX-21001` | `WF-INGEST-01` | `DCE-61001` | `COMPLETED` |

**`FRAMEWORK_STAGE_EXECUTION`** — same three stages; all **`COMPLETED`** when policy is “reject bad rows, continue file”:

| stage_exec_id | wf_exec_id | stage_id | status |
|---------------|------------|----------|--------|
| `STGEX-2101` | `WFEX-21001` | `STG-PARSE` | `COMPLETED` |
| `STGEX-2102` | `WFEX-21001` | `STG-VALID` | `COMPLETED` |
| `STGEX-2103` | `WFEX-21001` | `STG-PERSIST` | `COMPLETED` |

---

### 13.4 `JOB_CHUNK_*` (chunks usually still `DONE`)

| chunk_id | wf_exec_id | line_from | line_to | status |
|----------|------------|-----------|---------|--------|
| `CHK-211` | `WFEX-21001` | 1 | 5 | `DONE` |
| `CHK-212` | `WFEX-21001` | 6 | 10 | `DONE` |

*Chunk **DONE** means the chunk was **processed**, not that every line was valid. Line-level failure is in **`RECORD_EXECUTION_DATA`**.*

---

### 13.5 `RECORD_EXECUTION_DATA` — all 10 lines (mixed statuses)

*Same disposition as **§6.1**; column **`reason_or_error_message`** matches.*

| rec_id | wf_exec_id | line_no | chunk_id | loan_id | record_status | error_code | failed_rule_id | reason_or_error_message |
|--------|------------|---------|----------|---------|----------------|------------|----------------|-------------------------|
| `REC-B01` | `WFEX-21001` | 1 | `CHK-211` | L-3001 | `VALID` | — | — | — |
| `REC-B02` | `WFEX-21001` | 2 | `CHK-211` | L-3002 | `VALID` | — | — | — |
| `REC-B03` | `WFEX-21001` | 3 | `CHK-211` | L-3003 | `INVALID` | `VAL_NUMERIC` | `VR-AMT` | Amount field cannot be parsed as a decimal number (value: not-a-number). |
| `REC-B04` | `WFEX-21001` | 4 | `CHK-211` | L-3004 | `VALID` | — | — | — |
| `REC-B05` | `WFEX-21001` | 5 | `CHK-211` | L-3005 | `VALID` | — | — | — |
| `REC-B06` | `WFEX-21001` | 6 | `CHK-212` | L-3006 | `VALID` | — | — | — |
| `REC-B07` | `WFEX-21001` | 7 | `CHK-212` | L-3007 | `VALID` | — | — | — |
| `REC-B08` | `WFEX-21001` | 8 | `CHK-212` | L-3008 | `INVALID` | `VAL_LOV` | `VR-CCY` | Currency code JPY is not in the allowed list (USD, EUR, GBP). |
| `REC-B09` | `WFEX-21001` | 9 | `CHK-212` | L-3009 | `INVALID` | `VAL_RANGE` | `VR-AMT-GT0` | Amount must be strictly greater than zero. |
| `REC-B10` | `WFEX-21001` | 10 | `CHK-212` | L-3010 | `VALID` | — | — | — |

**Auditing / “what was processed vs not”:**  
- **Processed (read):** lines 1–10 (always 10 **`RECORD_EXECUTION_DATA`** rows if you record every line).  
- **Persisted:** only **`VALID`** rows — **no** `DATA_PERSISTENCE` row for `L-3003`, `L-3008`, `L-3009`.

---

### 13.6 `DATA_PERSISTENCE` — seven rows only (good data)

| persistence_id | load_event_id | wf_exec_id | loan_id | amount | currency | rec_id |
|----------------|----------------|------------|---------|--------|----------|--------|
| `DP-611` | `DCE-61001` | `WFEX-21001` | L-3001 | 125000.00 | USD | `REC-B01` |
| `DP-612` | `DCE-61001` | `WFEX-21001` | L-3002 | 98000.50 | USD | `REC-B02` |
| `DP-613` | `DCE-61001` | `WFEX-21001` | L-3004 | 77000.00 | USD | `REC-B04` |
| `DP-614` | `DCE-61001` | `WFEX-21001` | L-3005 | 210000.00 | USD | `REC-B05` |
| `DP-615` | `DCE-61001` | `WFEX-21001` | L-3006 | 333000.75 | USD | `REC-B06` |
| `DP-616` | `DCE-61001` | `WFEX-21001` | L-3007 | 15000.00 | GBP | `REC-B07` |
| `DP-617` | `DCE-61001` | `WFEX-21001` | L-3010 | 50000.00 | USD | `REC-B10` |

---

### 13.7 `FRAMEWORK_AUDIT_LOG` — optional row-level error events

| log_id | wf_exec_id | load_event_id | severity | event_type | message | rec_id |
|--------|------------|---------------|----------|------------|---------|--------|
| `LOG-B01` | `WFEX-21001` | `DCE-61001` | `ERROR` | `ROW_VALIDATION` | Line 3: amount not numeric (VR-AMT) | `REC-B03` |
| `LOG-B02` | `WFEX-21001` | `DCE-61001` | `ERROR` | `ROW_VALIDATION` | Line 8: currency JPY not in LOV (VR-CCY) | `REC-B08` |
| `LOG-B03` | `WFEX-21001` | `DCE-61001` | `WARN` | `ROW_VALIDATION` | Line 9: amount must be &gt; 0 (VR-AMT-GT0) | `REC-B09` |

*Some products log one event per bad row; others only **`RECORD_EXECUTION_DATA`**. Use both for strongest audit trail.*

---

### 13.8 `TEMP_OUT` — seven rows (mirrors accepted persistence)

Same pattern as §9: one **`TEMP_OUT`** row per **`DP-611`–`DP-617`** if you hand off only good rows.

---

### 13.9 Reconciliation checklist (auditing)

| Question | Where to look |
|----------|----------------|
| How many lines in the file? | 10 = count **`RECORD_EXECUTION_DATA`** for `wf_exec_id = WFEX-21001`. |
| How many accepted? | 7 = count `record_status = VALID` **or** count **`DATA_PERSISTENCE`**. |
| Which lines failed? | **`RECORD_EXECUTION_DATA`** where `INVALID` + **`line_no`**. |
| Why did they fail? | **`failed_rule_id`** + **`error_code`** (+ **`VALIDATION_RULES`** for text). |
| Full raw line for support? | **`raw_line`** on **`RECORD_EXECUTION_DATA`** (if column exists) or re-read S3. |
| File-level outcome? | **`DATA_CONNECT_LOAD_EVENT.status`** + optional summary counts. |

---

### 13.10 ID chain (Scenario B)

```text
DCE-61001
    ← WFEX-21001
            ├── STGEX-2101 / STGEX-2102 / STGEX-2103 (parse → validate → persist)
            ├── CHK-211, CHK-212 (both DONE)
            ├── REC-B01 … REC-B10 (10 rows; 3 × INVALID)
            ├── DP-611 … DP-617 (7 rows only)
            └── LOG-B01 … LOG-B03 (optional audit log)
```

---

## Revision

| Date | Note |
|------|------|
| 2026-03-27 | Sample file + 10-row walkthrough. |
| 2026-03-27 | §0 schema index; §12 where errors go (`RECORD_EXECUTION_DATA`, `FRAMEWORK_AUDIT_LOG`, not `*_AUDIT` config tables). |
| 2026-03-27 | §2 populated catalog tables (`SOURCE_REGISTRY`, `SOURCE_SCHEDULE`, `FEEDS`, `FIELDS`, `FEED_FIELD_ASSOCIATION`, `LIST_OF_VALUES`, `VALIDATION_RULES`, `EXTERNAL_DATASOURCE_CONFIG`, `FRAMEWORK_CONFIGURATION`, optional `FRAMEWORK_CONFIG_HOST`). |
| 2026-03-27 | §7: repeat `LIST_OF_VALUES` + `VALIDATION_RULES` sample rows; clarify “no new inserts” = no extra rows **during** the file run (rules pre-exist in §2/§7). |
| 2026-03-27 | §2 + §7: five `VALIDATION_RULES` rows (ID/NAME/DESCR/EXPRESSION/SOURCE); line-1 evaluation table; aligned with Oracle column names. |
| 2026-03-27 | §13 Scenario B: `lending-10rows-mixed-errors.psv` (3 invalid / 7 valid); full samples for load event, chunks, `RECORD_EXECUTION_DATA`, `DATA_PERSISTENCE`, `FRAMEWORK_AUDIT_LOG`, reconciliation checklist. |
| 2026-03-27 | §4 + §13.3: explicit `FRAMEWORK_STAGE_EXECUTION` rows (`STGEX-201`–`203`, `STGEX-2101`–`2103`). |
| 2026-03-27 | §12.2: expanded `FRAMEWORK_AUDIT_LOG` examples (lifecycle, stages, chunks, row validation, threshold, persist retry, engine error) + light two-row variant; note vs Scenario A all-valid. |
| 2026-03-27 | §6: mixed invalid rows + `reason_or_error_message`; §1–§10 aligned to `lending-10rows-mixed-errors.psv` (7 `DATA_PERSISTENCE` rows); §6.2 all-valid; §13.5 reasons aligned with §6.1. |
