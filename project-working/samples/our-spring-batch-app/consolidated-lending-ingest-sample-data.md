# Consolidated data model + sample data — lending ingest (Spring Batch)

**Purpose:** One place to see **tables and sample rows** for the walkthrough in **`brainstorm+rework-data-model-springbatch.md`**.  
**Formal data model (ER, entity groups, cardinality):** [`DATA-MODEL-lending-ingest.md`](./DATA-MODEL-lending-ingest.md).  
**Sample file:** `../lending-10rows-mixed-errors.psv` (10 data lines; **7** `VALID` / **3** `INVALID`).  
**First run ids:** `load_event_id = DCE-60001`, `job_execution_id = 20001` — **illustrative**; production uses surrogate keys / sequences as below.

---

## 0. Canonical schema (keys, types, indexes) — implementation-oriented

This section tightens the **sample** narrative into something you can implement in PostgreSQL. Adjust names to your naming standard; keep **join keys** stable so Spring Batch and ops queries stay simple.

### Identifiers & feeds

| Concept | Recommended type | Notes |
|--------|------------------|--------|
| `load_event_id` | `BIGSERIAL` or `UUID` | Natural keys like `DCE-60001` are fine for logs; **surrogate PK** avoids hot spots and long varchar PKs. |
| `feed_id` | `VARCHAR(64)` FK → `FEEDS(feed_id)` | Example: `FEED-LENDING-01`. |
| `job_execution_id` | `BIGINT` FK → `BATCH_JOB_EXECUTION.JOB_EXECUTION_ID` | Spring Batch–assigned. |
| `rec_id` | `BIGSERIAL` | One row per physical file line per job run. |
| `chunk_id` | `VARCHAR(64)` or `BIGINT` | Logical chunk label (e.g. `CHK-201`); optional FK to a **`CHUNK_MANIFEST`** table if you need line ranges and retry at chunk granularity. |
| `persistence_id` | `BIGSERIAL` | One row per persisted valid loan snapshot for this load. |

### `DATA_CONNECT_LOAD_EVENT`

| Column | Type | Nullable | Constraint / index |
|--------|------|----------|---------------------|
| `load_event_id` | `BIGSERIAL` | NO | PK |
| `job_execution_id` | `BIGINT` | YES → `BATCH_JOB_EXECUTION` | UNIQUE (optional): one terminal event per run |
| `feed_id` | `VARCHAR(64)` | NO | FK `FEEDS`, index for ops by feed |
| `s3_key` | `TEXT` | NO | |
| `status` | `VARCHAR(32)` | NO | e.g. `RUNNING`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED` |
| `created_at` | `TIMESTAMPTZ` | NO | default `now()` |

**Idempotency (file level):** Add **`s3_etag`** or **`s3_version_id`** (optional) and a **unique** constraint on `(feed_id, s3_key, s3_version_id)` or `(feed_id, s3_key, content_hash)` if you need “same key, new version” semantics. Otherwise enforce **at-most-one active run per load_event** in application logic + status checks.

### `RECORD_EXECUTION_DATA`

| Column | Type | Nullable | Constraint / index |
|--------|------|----------|---------------------|
| `rec_id` | `BIGSERIAL` | NO | PK |
| `job_execution_id` | `BIGINT` | NO | FK, **index** `(job_execution_id)` |
| `line_no` | `INT` | NO | **unique** `(job_execution_id, line_no)` |
| `chunk_id` | `VARCHAR(64)` | YES | index if you query by chunk |
| `loan_id` | `VARCHAR(64)` | YES | index if downstream lookups |
| `record_status` | `VARCHAR(16)` | NO | `VALID` / `INVALID` |
| `error_code` | `VARCHAR(64)` | YES | |
| `failed_rule_id` | `VARCHAR(64)` | YES | FK → `VALIDATION_RULES` optional |
| `reason` | `TEXT` | YES | alias for `reason_or_error_message` in samples |

**Scale:** This table is **one row per line** → largest table by row count. Plan **partitioning** by time via `job_execution_id` join to batch execution time, or by `load_event_id` / range partition on `rec_id` (see HLD).

### `DATA_PERSISTENCE`

| Column | Type | Nullable | Constraint / index |
|--------|------|----------|---------------------|
| `persistence_id` | `BIGSERIAL` | NO | PK |
| `load_event_id` | `BIGINT` or `UUID` | NO | FK, index |
| `job_execution_id` | `BIGINT` | NO | FK, index |
| `loan_id` | `VARCHAR(64)` | NO | business key + **index** for downstream |
| `amount` | `NUMERIC(18,2)` | NO | |
| `currency` | `CHAR(3)` | NO | |
| `rec_id` | `BIGINT` | NO | FK → `RECORD_EXECUTION_DATA` |

**Uniqueness:** For idempotent **record-level** loads, define whether `(load_event_id, loan_id)` or `(job_execution_id, loan_id)` is unique for a given feed policy.

### Optional: `CHUNK_MANIFEST` (if chunk-level retry/observability is first-class)

| Column | Type | Notes |
|--------|------|--------|
| `chunk_manifest_id` | `BIGSERIAL` | PK |
| `job_execution_id` | `BIGINT` | FK |
| `chunk_id` | `VARCHAR(64)` | matches `RECORD_EXECUTION_DATA.chunk_id` |
| `line_from` | `INT` | |
| `line_to` | `INT` | |
| `status` | `VARCHAR(32)` | `PENDING`, `DONE`, `FAILED` |

Not required for minimal pipeline; add when §10 chunk restart semantics need DB-backed state.

### Spring Batch metadata

Use **`schema-postgresql.sql`** from `spring-batch-core` as-is; do not invent parallel copies of `BATCH_*` tables.

---

## 1. Snapshot

| Item | Value |
|------|--------|
| File | `lending-10rows-mixed-errors.psv` → S3 key `input/lending-10rows-mixed-errors.psv` |
| Feed | `FEED-LENDING-01` |
| Invalid lines | **3** (amount not numeric), **8** (JPY), **9** (amount 0) |
| `RECORD_EXECUTION_DATA` | **10** rows (`REC-501` … `REC-510`) |
| `DATA_PERSISTENCE` | **7** rows (`DP-601` … `DP-607`) — no row for `L-3003`, `L-3008`, `L-3009` |

```text
DCE-60001 (DATA_CONNECT_LOAD_EVENT)
    ← JOB_EXECUTION_ID 20001 (BATCH_JOB_EXECUTION)
        ← JOB_INSTANCE_ID 10001 (BATCH_JOB_INSTANCE)
        → BATCH_JOB_EXECUTION_PARAMS (3 rows: loadEventId, s3Uri, feedId)
        → BATCH_STEP_EXECUTION ×3: parseInputStep → validateStep → persistStep (see §D)
        → RECORD_EXECUTION_DATA ×10
        → DATA_PERSISTENCE ×7
```

---

## 2. Table index (sample rows in this file)

| # | Table / area | Section |
|---|----------------|---------|
| A | Metadata (pre-loaded) | `SOURCE_REGISTRY` … `FRAMEWORK_CONFIGURATION` |
| B | Object storage | S3 (not a DB table) |
| C | Load event | `DATA_CONNECT_LOAD_EVENT` |
| D | Spring Batch | `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`, `BATCH_STEP_EXECUTION` |
| E | Logical chunks | column `chunk_id` on `RECORD_EXECUTION_DATA` only |
| F | Line outcomes | `RECORD_EXECUTION_DATA` |
| G | Good loans | `DATA_PERSISTENCE` |
| H | Optional | `TEMP_OUT`, `FRAMEWORK_AUDIT_LOG` |

---

## A. Metadata (before the file run)

### `SOURCE_REGISTRY`

| src_id | src_name | status |
|--------|----------|--------|
| SRC-PARTNER-01 | Synthetic Example Source | ACTIVE |

### `SOURCE_SCHEDULE`

| schedule_id | src_id | cron_or_desc |
|-------------|--------|----------------|
| SCH-DAILY-01 | SRC-PARTNER-01 | Daily 02:00 UTC |

### `FEEDS`

| feed_id | feed_name | src_id | schedule_id | file_pattern | delimiter |
|---------|-----------|--------|---------------|--------------|-----------|
| FEED-LENDING-01 | lending | SRC-PARTNER-01 | SCH-DAILY-01 | lending*.psv | \| |

### `FIELDS`

| field_id | field_name | data_type | max_len |
|----------|------------|-----------|---------|
| FLD-LOAN-ID | loan_id | STRING | 32 |
| FLD-AMT | amount | DECIMAL | 18 |
| FLD-CCY | currency | STRING | 3 |

### `FEED_FIELD_ASSOCIATION`

| feed_id | field_id | field_sequence | business_key |
|---------|----------|----------------|--------------|
| FEED-LENDING-01 | FLD-LOAN-ID | 1 | Y |
| FEED-LENDING-01 | FLD-AMT | 2 | N |
| FEED-LENDING-01 | FLD-CCY | 3 | N |

### `LIST_OF_VALUES`

| lov_id | name | values |
|--------|------|--------|
| LOV-CCY-01 | ISO_CURRENCY | USD,EUR,GBP |

### `VALIDATION_RULES`

| ID | NAME | EXPRESSION (short) |
|----|------|---------------------|
| VR-LOAN-ID | loan_id_present | notBlank(loan_id) |
| VR-LOAN-FMT | loan_id_format | pattern L-#### |
| VR-AMT | amount_numeric | decimal |
| VR-AMT-GT0 | amount_positive | amount > 0 |
| VR-CCY | currency_lov | in LOV-CCY-01 |

### `EXTERNAL_DATASOURCE_CONFIG`

| config_id | bucket_or_root | prefix |
|-----------|----------------|--------|
| EXT-INGEST-01 | ingest-bucket | input/ |

### `FRAMEWORK_CONFIGURATION`

| config_key | config_value |
|------------|----------------|
| chunk.max_lines | 5 |

---

## B. S3 (not in DB)

| Bucket / key |
|----------------|
| `s3://ingest-bucket/input/lending-10rows-mixed-errors.psv` |

---

## C. `DATA_CONNECT_LOAD_EVENT` (one row per file drop)

| load_event_id | job_execution_id | feed_id | s3_key | status (terminal) |
|---------------|------------------|---------|--------|-------------------|
| DCE-60001 | **20001** | FEED-LENDING-01 | input/lending-10rows-mixed-errors.psv | COMPLETED_WITH_ERRORS |

---

## D. Spring Batch (`BATCH_*`) — first run (concrete)

These tables are **real Spring Batch JDBC metadata** (see `schema-postgresql.sql` in **`spring-batch-core`**). Column names below match that schema; **ids** (`10001`, `20001`, **`201`–`203`**) are **examples** for this file only.

**What actually happens for `lending-10rows-mixed-errors.psv`**

1. You build **`JobParameters`**: `loadEventId=DCE-60001`, `s3Uri=…`, `feedId=FEED-LENDING-01`, plus a **run id** (e.g. long timestamp) so this file is a **new** job instance.
2. **`JobLauncher.run(job, parameters)`** → Spring inserts **`BATCH_JOB_INSTANCE`** (one row: “this job + this parameter set has this identity”).
3. Then **`BATCH_JOB_EXECUTION`** (one row: “this attempt to run the job”, **`JOB_EXECUTION_ID=20001`** — this is what **`DATA_CONNECT_LOAD_EVENT`** and **`RECORD_EXECUTION_DATA`** store).
4. Then **`BATCH_JOB_EXECUTION_PARAMS`** (one row per parameter — **3** rows here).
5. As the job runs, **`BATCH_STEP_EXECUTION`** gets **one row per `@Bean` Step**. This doc assumes **three** steps — **`parseInputStep`** → **`validateStep`** → **`persistStep`** — so **three** rows (**`STEP_EXECUTION_ID`** **201**, **202**, **203**). Invalid file lines are **`INVALID`** in **`RECORD_EXECUTION_DATA`**, not Spring **`skip`** rows → **`READ_SKIP_COUNT`**, **`PROCESS_SKIP_COUNT`**, **`WRITE_SKIP_COUNT`** stay **0** unless you use **`SkipPolicy`**.

**Questions each table answers**

| Table | Plain question |
|-------|----------------|
| `BATCH_JOB_INSTANCE` | “Which **job definition** + **which parameter identity** is this?” (restartable identity) |
| `BATCH_JOB_EXECUTION` | “Which **single run** of that instance? Did it finish? Exit code?” |
| `BATCH_JOB_EXECUTION_PARAMS` | “What exact **`JobParameters`** were passed for run **20001**?” (ops: find file without joining your schema) |
| `BATCH_STEP_EXECUTION` | “Per **Step**, how many items **read / written / skipped**?” (line counts live **here**, not on `BATCH_JOB_EXECUTION`) |

---

### `BATCH_JOB_INSTANCE` — one row (this file = new instance)

| JOB_INSTANCE_ID | VERSION | JOB_NAME | JOB_KEY |
|-----------------|--------|----------|---------|
| **10001** | 0 | ingestLendingFileJob | `8f3e2a1b4c5d6e7f8091a2b3c4d5e6f7` *(example 32-char hex; Spring computes from **identifying** `JobParameters` — opaque idempotency key)* |

**Concrete meaning:** Job name **`ingestLendingFileJob`** is the `@Configuration` job bean. **`JOB_KEY`** distinguishes “same job, different file” so the next drop does **not** reuse **`10001`**.

---

### `BATCH_JOB_EXECUTION` — one row (this run)

| JOB_EXECUTION_ID | VERSION | JOB_INSTANCE_ID | CREATE_TIME | START_TIME | END_TIME | STATUS | EXIT_CODE | EXIT_MESSAGE |
|------------------|---------|-----------------|-------------|------------|----------|--------|-----------|--------------|
| **20001** | 2 | **10001** | 2026-03-27 02:00:01 | 2026-03-27 02:00:02 | 2026-03-27 02:00:08 | COMPLETED | COMPLETED | *(null)* |

**Concrete meaning:** **`20001`** is the id you persist on **`DATA_CONNECT_LOAD_EVENT.job_execution_id`** and **`RECORD_EXECUTION_DATA.job_execution_id`**. There is **no** `READ_COUNT` on this row — counts are on **`BATCH_STEP_EXECUTION`**.

---

### `BATCH_JOB_EXECUTION_PARAMS` — three rows (one per `JobParameter` you pass)

Spring Batch 5 JDBC columns: **`PARAMETER_NAME`**, **`PARAMETER_TYPE`**, **`PARAMETER_VALUE`**, **`IDENTIFYING`**.  
**`IDENTIFYING = 'Y'`** means that parameter is part of the **job instance key** (with the others marked **`Y`**) — a different `s3Uri` or `loadEventId` ⇒ different **`JOB_INSTANCE_ID`**.

| JOB_EXECUTION_ID | PARAMETER_NAME | PARAMETER_TYPE | PARAMETER_VALUE | IDENTIFYING |
|------------------|----------------|----------------|-----------------|-------------|
| 20001 | loadEventId | STRING | DCE-60001 | Y |
| 20001 | s3Uri | STRING | s3://ingest-bucket/input/lending-10rows-mixed-errors.psv | Y |
| 20001 | feedId | STRING | FEED-LENDING-01 | Y |

In production you often add a **`runId`** (long timestamp) as a fourth parameter so every drop is unique even when reprocessing the same key; each extra parameter is **another row** in this same table.

---

### `BATCH_STEP_EXECUTION` — **three** `@Bean` Steps (parse → validate → persist) — default for this doc

The job wires **`parseInputStep`** → **`validateStep`** → **`persistStep`** (order in **`JobFlow`** / **`@Bean` job**). Spring inserts **one `BATCH_STEP_EXECUTION` row per step**, in run order.

Assume **`commit-interval=5`** on each step → **`COMMIT_COUNT = 2`** per step (two chunks for 10 lines). Invalid lines **3 / 8 / 9** are **`INVALID`** in **`RECORD_EXECUTION_DATA`** after **`validateStep`**; **`persistStep`**’s writer only inserts **VALID** loans → **`DATA_PERSISTENCE`** has **7** rows.

| STEP_EXECUTION_ID | VERSION | STEP_NAME | JOB_EXECUTION_ID | START_TIME | END_TIME | STATUS | COMMIT_COUNT | READ_COUNT | FILTER_COUNT | WRITE_COUNT | READ_SKIP_COUNT | WRITE_SKIP_COUNT | PROCESS_SKIP_COUNT | EXIT_CODE |
|-------------------|---------|-----------|------------------|------------|----------|--------|--------------|------------|--------------|---------------|-----------------|------------------|---------------------|-----------|
| **201** | 1 | parseInputStep | **20001** | 2026-03-27 02:00:03 | 2026-03-27 02:00:04 | COMPLETED | **2** | **10** | 0 | **10** | 0 | 0 | 0 | COMPLETED |
| **202** | 1 | validateStep | **20001** | 2026-03-27 02:00:04 | 2026-03-27 02:00:06 | COMPLETED | **2** | **10** | 0 | **10** | 0 | 0 | 0 | COMPLETED |
| **203** | 1 | persistStep | **20001** | 2026-03-27 02:00:06 | 2026-03-27 02:00:08 | COMPLETED | **2** | **10** | 0 | **7** | 0 | 0 | 0 | COMPLETED |

**What each step is doing for this PSV**

| Step | `READ_COUNT` | `WRITE_COUNT` | Meaning |
|------|--------------|----------------|---------|
| **parseInputStep** | 10 | 10 | Read **10** raw lines from the PSV; write **10** parsed items to the next step (e.g. staging or direct handoff). |
| **validateStep** | 10 | 10 | Read **10** parsed items; write **10** validation outcomes → **10** **`RECORD_EXECUTION_DATA`** rows (**7** `VALID`, **3** `INVALID`). |
| **persistStep** | 10 | **7** | Read **10** outcomes (or **10** “candidates”); **`ItemWriter`** inserts **7** **`DATA_PERSISTENCE`** rows — **only** valid loans. |

**Legacy alias:** narrative **`STGEX-201`…`203`** (older docs) ≈ **`STEP_EXECUTION_ID`** **201**…**203** here.

---

## E. Logical chunks (not separate `BATCH_*` tables)

| chunk_id | job_execution_id | line_from | line_to |
|----------|------------------|-----------|---------|
| CHK-201 | 20001 | 1 | 5 |
| CHK-202 | 20001 | 6 | 10 |

---

## F. `RECORD_EXECUTION_DATA` (10 rows — one per file line)

| rec_id | job_execution_id | line_no | chunk_id | loan_id | record_status | error_code | failed_rule_id | reason_or_error_message |
|--------|------------------|---------|----------|---------|---------------|------------|----------------|-------------------------|
| REC-501 | 20001 | 1 | CHK-201 | L-3001 | VALID | — | — | — |
| REC-502 | 20001 | 2 | CHK-201 | L-3002 | VALID | — | — | — |
| REC-503 | 20001 | 3 | CHK-201 | L-3003 | INVALID | VAL_NUMERIC | VR-AMT | Amount not numeric (not-a-number). |
| REC-504 | 20001 | 4 | CHK-201 | L-3004 | VALID | — | — | — |
| REC-505 | 20001 | 5 | CHK-201 | L-3005 | VALID | — | — | — |
| REC-506 | 20001 | 6 | CHK-202 | L-3006 | VALID | — | — | — |
| REC-507 | 20001 | 7 | CHK-202 | L-3007 | VALID | — | — | — |
| REC-508 | 20001 | 8 | CHK-202 | L-3008 | INVALID | VAL_LOV | VR-CCY | JPY not in allowed list. |
| REC-509 | 20001 | 9 | CHK-202 | L-3009 | INVALID | VAL_RANGE | VR-AMT-GT0 | Amount must be > 0. |
| REC-510 | 20001 | 10 | CHK-202 | L-3010 | VALID | — | — | — |

---

## G. `DATA_PERSISTENCE` (7 rows — VALID only)

| persistence_id | load_event_id | job_execution_id | loan_id | amount | currency | rec_id |
|------------------|---------------|------------------|---------|--------|----------|--------|
| DP-601 | DCE-60001 | 20001 | L-3001 | 125000.00 | USD | REC-501 |
| DP-602 | DCE-60001 | 20001 | L-3002 | 98000.50 | USD | REC-502 |
| DP-603 | DCE-60001 | 20001 | L-3004 | 77000.00 | USD | REC-504 |
| DP-604 | DCE-60001 | 20001 | L-3005 | 210000.00 | USD | REC-505 |
| DP-605 | DCE-60001 | 20001 | L-3006 | 333000.75 | USD | REC-506 |
| DP-606 | DCE-60001 | 20001 | L-3007 | 15000.00 | GBP | REC-507 |
| DP-607 | DCE-60001 | 20001 | L-3010 | 50000.00 | USD | REC-510 |

---

## H. Optional tables (not expanded here)

- **`TEMP_OUT`** — up to **7** rows referencing `DP-601`…`DP-607` if you hand off accepted rows.
- **`FRAMEWORK_AUDIT_LOG`** — optional DB log lines; see full brainstorm doc.

---

## Join keys (quick reference)

| From | To |
|------|-----|
| `DATA_CONNECT_LOAD_EVENT.job_execution_id` | `BATCH_JOB_EXECUTION.JOB_EXECUTION_ID` |
| `RECORD_EXECUTION_DATA.job_execution_id` | `BATCH_JOB_EXECUTION.JOB_EXECUTION_ID` |
| `DATA_PERSISTENCE.load_event_id` | `DATA_CONNECT_LOAD_EVENT.load_event_id` |
| `DATA_PERSISTENCE.rec_id` | `RECORD_EXECUTION_DATA.rec_id` |
| `BATCH_STEP_EXECUTION.JOB_EXECUTION_ID` | `BATCH_JOB_EXECUTION.JOB_EXECUTION_ID` |

---

## Source

Aligned with **`brainstorm+rework-data-model-springbatch.md`**. Column types and additional tables: **`DATA-MODEL-lending-ingest.md`** and **`reference-10-rows-data-model.md`**.
