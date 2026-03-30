# High-Level Design — Lending File Ingest (Spring Batch on AWS)

**Deliverable:** Single Markdown HLD aligned to `hld-prompt`.  
**Data model:** [`../DATA-MODEL-lending-ingest.md`](../DATA-MODEL-lending-ingest.md) (ER, domains, cardinality); [`../consolidated-lending-ingest-sample-data.md`](../consolidated-lending-ingest-sample-data.md) (§0 canonical schema + sample rows).  
**Diagram assets:** [`diagrams/`](./diagrams/README.md) (Mermaid sources for draw.io recreation).

---

## 1. Executive Summary

We are building a **batch file processing platform** for lending feeds: files land in **S3** on varying schedules and sizes; **Control-M** is the **orchestrator** and submits work to **AWS Batch** (Spring Batch on **ECS** compute). **PostgreSQL** holds Spring Batch metadata, per-line outcomes (`RECORD_EXECUTION_DATA`), and persisted valid rows (`DATA_PERSISTENCE`).

The **primary risk** is **correlated load**: many files arriving together can spawn too many concurrent jobs, overwhelming PostgreSQL and causing instability or duplicate processing. The design centers on an explicit **admission-control knob** (global **max concurrent batch jobs** interacting with the database), **Control-M–driven** scheduling, **idempotent** keys at file and record level, **chunk-oriented** processing with clear transaction boundaries, and **strong observability**.

**Service expectation (stakeholder input):** **~95% of files** should complete end-to-end processing in **≤ 10 seconds**; a **long tail** of larger or hotter files may exceed that — capacity, chunk sizing, and concurrency limits must be tuned against measured p95/p99, not only the 95% target.

---

## 2. Assumptions

| Area | Assumption | Rationale |
|------|------------|-----------|
| Orchestration | **Control-M** is the system of record for **when** and **what** runs; it enforces dependencies and can enforce **job concurrency** caps at the orchestration layer. | User requirement: avoid unbounded parallel DB load. |
| Runtime SLA | **95%** of files finish within **10s**; remainder is **long tail** (bigger files, cold start, DB contention). | Drives chunk size, pool sizing, and alerting (SLO vs error budget). |
| Region | **Single AWS region** for initial design; DR is a **future** decision unless mandated. | §18 cost; add second region in roadmap if RTO/RPO required. |
| Data store | **PostgreSQL** is the sole transactional store for batch metadata and business tables listed in the consolidated model. | Simplifies consistency story. |
| File formats | PSV (and similar) with **feed metadata** in `FEEDS` / `FEED_FIELD_ASSOCIATION`. | Matches sample data model. |
| Identity | Production uses **surrogate keys** where §0 recommends (e.g. `BIGSERIAL` / UUID) vs display ids like `DCE-60001`. | Scalability and join performance. |

**Open numeric placeholders** (tune in non-prod load tests): peak **files/hour**, absolute **max concurrent jobs** (e.g. 2–20), **p95 file size** (MB). These feed queue depth and AWS Batch job queue limits.

---

## 3. Identified Gaps / Open Questions

| Gap | Impact | Suggested resolution |
|-----|--------|----------------------|
| Exact **max concurrent jobs** number | Core to DB safety | Set per environment; start conservative (e.g. **N=2–4** on shared DB), increase with metrics. |
| **S3 notifications** vs **polling** | Trigger latency | Prefer **S3 → EventBridge/SQS** only as **signal**; **Control-M** still owns schedule; avoid duplicate triggers without idempotency. |
| **Regulatory / PII** classification | Security §17 | Confirm retention, encryption, and access for lending data. |
| **Replay policy** | Who may replay, and with what new `JobParameters` | Document in ops runbook; tie to `runId` / `load_event_id`. |
| **Multi-tenant feeds** | Isolation | If many partners share one DB, consider **schema per feed** or **strong feed_id** partitioning. |
| **`RECORD_EXECUTION_DATA` retention** | Storage growth | Archive/partition strategy required before multi-year runs. |

---

## 4. High-Level Architecture Diagram

**Separate draw.io views** (see [`diagrams/`](./diagrams/README.md)):

1. **Deployment / infrastructure** — [`diagrams/deployment.mmd`](./diagrams/deployment.mmd): Control-M, AWS Batch, ECS, S3, PostgreSQL, observability.
2. **Logical data flow** — [`diagrams/data-flow.mmd`](./diagrams/data-flow.mmd): S3 → `DATA_CONNECT_LOAD_EVENT` → Spring Batch metadata → `RECORD_EXECUTION_DATA` → `DATA_PERSISTENCE`.
3. **Trigger, admission, retry** — [`diagrams/trigger-retry.mmd`](./diagrams/trigger-retry.mmd): Control-M submit → concurrency gate → Batch → DB → retry/replay.

Included concerns: **S3 ingestion**, **Control-M role**, **AWS Batch + ECS**, **Spring Batch**, **DB interactions**, **audit**, **retry/replay**.

---

## 5. Orchestration Design

| Component | Responsibility |
|-----------|----------------|
| **Control-M** | Schedules and dependencies; **submits** AWS Batch jobs (or invokes wrapper) with **JobParameters** (`loadEventId`, `s3Uri`, `feedId`, `runId`). Enforces **max concurrent** ingest jobs **at orchestration level** (the **knob**): e.g. only **N** overlapping file jobs globally or per feed group. |
| **S3 / EventBridge (optional)** | **Not** the primary scheduler. If used: **ObjectCreated** → SQS/EventBridge for **visibility** or **late binding** only; **admission control** still limits how many Batch jobs run. Prevents “S3 storm” from directly equaling “Batch job storm.” |
| **AWS Batch** | Queues and runs **disposable** job attempts on **ECS**; retries with backoff at infrastructure layer; respects **vCPU/memory** and **job queue** depth. |
| **Spring Batch** | **Application** orchestration: steps, chunks, transactions, restart metadata in `BATCH_*` tables. |

**Who triggers jobs?** **Control-M** (primary). **Who controls concurrency?** **Control-M policy** (hard knob) + **AWS Batch job queue** limits + optional **semaphore in DB or distributed lock** for extra safety (see §6). **Who handles retries?** **Transient failures:** AWS Batch retry + Spring Batch step retry policy; **business/validation:** recorded in `RECORD_EXECUTION_DATA`; **replay:** new job run with new `runId` and idempotent persistence rules (§8).

---

## 6. Burst Handling / Admission Control

**Design goal:** Whether **2**, **20**, or **200** files appear, only **up to N** jobs may **actively hammer PostgreSQL** at once (N configurable).

| Scale | Behavior |
|-------|----------|
| **2 files** | Both may run if **N ≥ 2** and DB healthy; if **N = 1**, second waits in **Control-M** queue or **AWS Batch** queue (backlogged, not failed). |
| **20 files** | **Admission queue**: excess work waits; **prioritization** optional (e.g. VIP feed first via separate Control-M job chain or priority queue). |
| **200 files** | Same pattern: **bounded parallelism**; monitor **queue age** and **DB**; scale **read throughput** only after DB can absorb write load (§7). |

**Mechanisms:**

- **Knob 1 — Control-M:** Global (or per **feed group**) **limit concurrent file ingest jobs**. This is the primary **business-operable** throttle.
- **Knob 2 — AWS Batch:** **Job queue** `maxvCpus` / **array size** / concurrent job count caps per compute environment.
- **Knob 3 — Optional queue:** **SQS** or **control table** (`PENDING`, `CLAIMED`) if you need **worker-driven** dequeue with **lease** semantics; useful when Control-M cannot express dynamic backpressure alone.

**Prioritization:** Separate Control-M **job networks** or **priority queues** in Batch for critical feeds.

---

## 7. Scalability

| Bottleneck | Risk | Mitigation |
|------------|------|------------|
| **Spring Batch `BATCH_*` metadata** | Sync writes every chunk commit | Tune **chunk size**; reduce metadata churn; use **batch inserts** where framework allows; consider **job repository tuning** (indexes per Spring docs). |
| **`RECORD_EXECUTION_DATA` growth** | Largest table by row | **Partition** (range on time via job end, or by `rec_id` range); **archive** cold partitions to S3 (§16). |
| **PostgreSQL writes** | CPU / IO / connections | **Admission control** (§6); **connection pool** per task (not unbounded); **read replicas** for reporting only — **writes stay primary**. |
| **ECS / AWS Batch** | Cost vs speed | Right-size CPU/memory; **avoid over-scaling** job count when DB is the cap. |

**95% in 10s:** Requires **small files** in the common case, **warm pools** or fast startup, **chunk commit** cost << 10s, and **no lock convoys** from concurrent jobs (§12).

---

## 8. Idempotency

| Level | Mechanism |
|-------|-----------|
| **File** | **Unique** business constraint: `(feed_id, s3_key, content_hash or version)` or idempotent **`load_event_id`** creation **before** job start; reject duplicate **active** runs for same semantic file. |
| **Job** | Spring Batch **`JOB_INSTANCE`** from **identifying** `JobParameters`; include **`runId`** for distinct attempts; **restart** reuses execution context. |
| **Record** | **`DATA_PERSISTENCE`**: define **unique** key per policy (e.g. `(load_event_id, loan_id)` **or** `(job_execution_id, loan_id)`); use **upsert** or **insert on conflict do nothing** where replay must not duplicate business rows. |

---

## 9. Data Model Review

**`RECORD_EXECUTION_DATA`:** High volume — **PK** on `rec_id`, **unique (job_execution_id, line_no)**, index on `(job_execution_id)`. **Partitioning** by month or by hash of `job_execution_id` if queries are time-bounded. **Retention:** roll off to **cold storage** after SLA for investigations.

**`DATA_PERSISTENCE`:** Smaller than line table; **index** `loan_id` for downstream; **FK** to `rec_id` for traceability. **Uniqueness** rule must match replay semantics (§8).

**Indexing:** All **FKs** used in joins from ops dashboards; **partial** indexes on `record_status` if large INVALID scans.

**Partitioning:** Prefer **range** on **ingest time** derived from `BATCH_JOB_EXECUTION` end time or add **`ingested_at`** to large tables for pruning.

**Retention:** **Online** hot window (e.g. 90 days full line detail); **archive** aggregates + invalid samples longer if policy allows.

**Improvements (from §0):** Optional **`CHUNK_MANIFEST`** when chunk-level restart is DB-backed; optional **`s3_etag`** on `DATA_CONNECT_LOAD_EVENT` for file-level dedup.

---

## 10. Chunk-Based Processing Design (Critical)

### A. Chunk model

- **Default:** Align **`chunk.max_lines`** (e.g. **5** in sample) with **`commit-interval`** so **one chunk = one transaction** per step where configured.
- **Tradeoffs:** Larger chunks → fewer commits, **less** metadata overhead, **higher** memory and longer rollback blast radius. Smaller chunks → more commits, **more** DB chatter — hurts the **10s** target for small files if taken too far.

### B. Transaction boundaries

- **Pattern:** **Chunk = transaction** for the persist step’s writer (and validate step if writing `RECORD_EXECUTION_DATA` in same transaction boundary — choose **one** consistent approach per step).
- **Rollback:** Failed chunk rolls back **only** that chunk’s writes; Spring Batch **restart** skips **completed** chunks via step execution state.

### C. Chunk ↔ data model

- **`chunk_id`** on each `RECORD_EXECUTION_DATA` row links lines to logical chunk; **`CHK-201`** style ids are stable within a run.
- **`CHUNK_MANIFEST`** optional table if ops needs **line_from/line_to** and status without scanning lines.

### D. Failure & retry

- **Retry chunk** on transient DB errors (deadlock, timeout) with **limit**; **skip** policy only for **poison** records if business allows — otherwise **INVALID** row in `RECORD_EXECUTION_DATA` preferred over silent skip.

### E. Idempotency at chunk level

- **Re-run** after failure: Spring Batch **restart** replays **failed** chunk; **writers** must use **deterministic keys** so replay does not duplicate `DATA_PERSISTENCE` rows (§8).

### F. Performance

- **Batch JDBC** or multi-row **INSERT** for `RECORD_EXECUTION_DATA` / `DATA_PERSISTENCE` per chunk where possible.
- **Connection pool:** size ≈ **concurrent chunks × concurrent jobs** upper bound, capped below DB **max_connections**.

### G. Parallelism

- **Single-threaded steps** default for **simpler** locking; **multi-threaded step** or **partitioned step** only if **profiling** shows CPU-bound parsing and **DB** can sustain **parallel writers** (often **not** on shared OLTP).

### H. Observability

- Per-chunk **timers** in logs/metrics; **Spring Batch** `BATCH_STEP_EXECUTION` **COMMIT_COUNT**, **READ_COUNT**, **WRITE_COUNT**.

### I. Restartability

- **Spring Batch** restart from **last committed** chunk; document **exact** `JobParameters` and **execution id** for ops.

---

## 11. Observability

| Signal | What |
|--------|------|
| **Metrics** | Job duration p50/p95/p99; **DB** wait time; **Batch** queue depth; **active jobs** count vs cap; **chunk** duration. |
| **Logs** | Structured: `load_event_id`, `job_execution_id`, `feed_id`, `s3_key`, step name. |
| **Tracing** | OpenTelemetry **trace id** across Control-M wrapper (if any) → Batch → Spring Boot. |

**Questions answered:** **Which job?** `BATCH_JOB_EXECUTION` + `DATA_CONNECT_LOAD_EVENT`. **Which step slow?** `BATCH_STEP_EXECUTION` durations. **Which rows failed?** `RECORD_EXECUTION_DATA` where `record_status = INVALID`.

---

## 12. Concurrency & Database Safety

- **Concurrent jobs:** Bounded by §6; **no** unbounded pool.
- **Locks:** Same feed + same business keys → **unique constraints** detect races; **avoid** long transactions spanning whole file for multi-GB files — **chunk** commits.
- **Connection pool:** **Hikari** `maximumPoolSize` × **max jobs** ≤ safe fraction of **PostgreSQL max_connections** (leave headroom for admin + other apps).
- **Deadlocks:** **Consistent lock order** (e.g. persist `loan_id` ascending within chunk); **retry** transient deadlocks once.

---

## 13. Backpressure & Flow Control

When **DB is slow:** **stop launching** new jobs from Control-M (policy), **reduce N**, or **pause** queue consumers. **Metrics:** DB **latency**, **connection wait**, **Batch queue age**.

**Rate limiting:** Admission at **Control-M** + **Batch**; optional **token bucket** in app if needed.

**Buffering:** **SQS** or Batch **queue** holds excess **work** without dropping files in S3.

**Retry:** Exponential backoff at **Batch**; **idempotent** writes prevent duplicate business data.

---

## 14. Failure & Recovery

- **Restart:** Spring Batch **restart** from last successful chunk; **document** parameter rules.
- **Checkpointing:** Framework-managed via **`BATCH_STEP_EXECUTION`** and **execution context**.
- **Replay:** **New** `JobParameters` (`runId`) + **dedup** keys for persistence; **never** blindly rerun without idempotency plan.

---

## 15. Schema Evolution

- **New columns:** **Additive** migrations; default values; feature flags for new validation rules in `VALIDATION_RULES`.
- **Feed changes:** **Version** feeds or **FEED_FIELD_ASSOCIATION** changes with **compat** window; reject unknown columns to **INVALID** with clear **error_code**.

---

## 16. Storage Strategy

- **Hot:** Recent **`RECORD_EXECUTION_DATA`** partitions online.
- **Cold:** **Archive** to **S3** (Parquet/CSV) with **partition** keys; **metadata** row in catalog table optional.
- **Spring Batch** old executions: **purge** job per retention policy (Spring Batch admin patterns).

---

## 17. Security

- **Encryption:** **S3** SSE-KMS; **PostgreSQL** encryption at rest (RDS); **TLS** in transit.
- **Access:** **IAM roles** for Batch tasks; **least privilege** on S3 prefix per feed; **no** shared DB user for ad-hoc.
- **Audit:** **`FRAMEWORK_AUDIT_LOG`** (optional); **CloudTrail** for AWS API; **who** triggered Control-M job where integrated.

---

## 18. Cost Optimization

- **Compute:** **Fargate/Spot** in Batch where acceptable; **right-size** CPU/memory to meet **10s** for p95 workload without permanent oversizing.
- **DB:** **Avoid** over-provisioning driven by **uncapped** parallelism — **admission control** saves DB cost.
- **Storage:** **Partition drop** + **S3 lifecycle** for archives.

---

## 19. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| **Thundering herd** on S3 | Admission control + optional queue; no 1:1 S3 event → job without gate. |
| **DB overload** | **N concurrent jobs** knob; pool limits; chunk tuning. |
| **Duplicate processing** | File + record **unique** constraints; idempotent writers. |
| **Long tail breaks SLA** | Separate **alerts** for p99; **capacity** plan for large files; **SLA** stated as 95% not 100%. |
| **Poison file** | **Max lines** / **size** guard; **fail** job with clear status; **quarantine** in S3. |

---

## 20. Final Recommended Architecture

**Opinionated choice:**

1. **Control-M** is the **only** orchestrator for **starting** file jobs; it exposes the **max concurrent ingest jobs** knob (globally or per group).
2. **AWS Batch** on **ECS** runs **one Spring Boot container = one file job** (typical); **optional** S3 events for **observability** only.
3. **PostgreSQL** holds **Spring Batch metadata**, **`DATA_CONNECT_LOAD_EVENT`**, **`RECORD_EXECUTION_DATA`**, **`DATA_PERSISTENCE`** with **§0** keys and **unique** constraints for idempotency.
4. **Chunk** processing with **commit-interval = chunk size**, **batch JDBC** writes, **partitioning** plan for line table, **archive** after retention.
5. **Observability** and **alerts** on **queue depth**, **job duration vs 10s SLO**, and **DB** health.

---

## Stakeholder review checklist

| Audience | Sections |
|----------|----------|
| Product / Ops | §6 burst, prioritization, §14 recovery |
| Security | §17 |
| Finance / Capacity | §18 |
| Engineering | §9, §10, §12 |

**Open questions** from §3 should be tracked in a **decision log** or backlog.

---

## Expectation (from prompt)

Production-first; **tradeoffs** stated; **implementation-oriented**; diagrams and tables referenced above; **cross-reference:** idempotency §8 + §10.E; backpressure §6 + §13.
