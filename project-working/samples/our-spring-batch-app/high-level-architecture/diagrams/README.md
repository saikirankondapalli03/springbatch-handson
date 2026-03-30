# HLD diagrams (draw.io assets)

Recreate these three views in **draw.io** (diagrams.net) using **C4-style** where helpful and **AWS Architecture Icons** for cloud components. Source sketches below are **Mermaid** (`.mmd`) for version control; you can translate them manually into draw.io or use a Mermaid-to-diagram workflow.

| # | File | View |
|---|------|------|
| 1 | [deployment.mmd](./deployment.mmd) | Infrastructure: Control-M → AWS Batch/ECS → S3/PostgreSQL, observability |
| 2 | [data-flow.mmd](./data-flow.mmd) | Logical data: S3 → `DATA_CONNECT_LOAD_EVENT` → Spring Batch → `RECORD_EXECUTION_DATA` → `DATA_PERSISTENCE` |
| 3 | [trigger-retry.mmd](./trigger-retry.mmd) | Sequence: submit job, admission control, success vs retry/replay |

**Link from the HLD:** [HLD-lending-batch-ingest.md](../HLD-lending-batch-ingest.md) §4 references this folder.
