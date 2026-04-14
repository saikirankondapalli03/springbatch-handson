# Multi-step processes — interview prep

Study notes on **sagas**, **workflow engines**, and **durable execution** for system design. Original material drew on Hello Interview’s multi-step pattern and common production practice.

---

## One-minute pitch (open with this)

Real systems coordinate many flaky steps (payments, inventory, humans, webhooks) over **seconds to days**. A naive sequence of service calls loses state on crash, cannot route async callbacks cleanly, and mixes **infrastructure concerns** (retries, crashes) with **business rules** (refunds, compensation). **Workflow engines** and **durable execution** centralize orchestration, history, and recovery so business logic stays readable. Tradeoff: you operate another distributed system and pay complexity—only where the problem warrants it.

---

## Why interviewers care

- Multi-step flows drive **on-call** pain when done ad hoc.
- They test whether you separate **orchestration** from **side effects**, handle **partial failure**, and know **idempotency** and **long waits** without polling.

Optional reference: Jimmy Bogard’s *“Six Little Lines of Fail”* (why simple sequences break in distributed systems).

---

## Canonical example (have this ready)

**Order fulfillment:** charge payment → reserve inventory → ship → email → (optional) human steps.

Failure modes: any step fails or times out; payment gateway callbacks async; process **mid-flight** on deploy or crash; need **compensation** (e.g. refund if inventory fails).

---

## Approaches (simple → sophisticated)

### 1. Single-server orchestration

**What:** One request path; call services A, then B, then C in process.

**When it’s enough:** Low coordination need, sync client, acceptable to fail the whole request.

**Why it breaks at scale:** No memory after restart; hard to attach **webhooks** to the “right” in-flight request; scaling out duplicates work unless you add shared state—then you are hand-rolling a state machine.

**Interview line:** “Fine for the happy path; wrong tool when you need **durable progress** and **compensation** across steps.”

### 2. DIY: DB checkpoints + pub/sub

**What:** Persist state after each step; route callbacks via messaging; multiple API instances read state.

**Problems:** Who **claims** stuck work? Compensation logic **sprawls**; you maintain a custom distributed state machine. Often becomes operational debt.

**Interview line:** “This is reinventing what a workflow engine gives you—history, replay, and consistent recovery semantics.”

### 3. Event sourcing (over a durable log)

**What:** Append **events** (`OrderPlaced`, `PaymentCharged`, …); workers consume and emit events. Kafka (common) or Redis Streams (smaller scale).

**vs EDA:** EDA = decouple with topics. Event sourcing = **history** as source of truth + often drives **next steps** via replay/processing.

**Pros:** Fault tolerance (another worker picks up), scale-out, audit trail, room to evolve steps.

**Cons:** You build **event store + routing + observability** yourself; debugging **lineage** (“why this `PaymentFailed`?”) gets hard without strong tooling.

**Interview line:** “Right direction for reliability; heavy to own end-to-end—often people adopt a **workflow product** instead.”

### 4. Workflow systems / durable execution

**What:** Describe a **workflow**; engine stores **checkpoints** and **history**, resumes after failure, balances **activities** across workers.

Two flavors:

| | **Code-first (durable execution)** | **Declarative (managed)** |
|---|-----------------------------------|---------------------------|
| **Examples** | Temporal (Cadence), similar engines | AWS Step Functions, GCP Workflows |
| **Describe** | Workflow as code (deterministic) | State machine / DAG (JSON, YAML) |
| **Pros** | Expressive; familiar to devs | Visual diagrams; less ops on cloud |
| **Cons** | Run and operate clusters (e.g. Temporal) | Less expressive; may push logic into Lambdas |

**Shared idea:** After each step, engine persists progress; another worker can **replay** and continue without redoing successful side effects (activities recorded in history).

---

## Durable execution (e.g. Temporal) — concepts to name

| Concept | Say this |
|--------|----------|
| **Workflow** | High-level control flow; must be **deterministic** (same inputs + history → same branches). Enables **replay**. |
| **Activity** | Real I/O (call payment API, DB); **non**-deterministic bits live here; should be **idempotent** under retry. |
| **History** | Append-only record of decisions and activity results; replay uses it instead of re-executing completed activities. |
| **Signals** | External events (human approved, doc signed, webhook) without polling; workflow waits efficiently. |
| **Deployment shape** | Server/orchestration + **history store** + **worker pools** (workflow vs activity workers). |

**Why workflow code looks like “single server” again:** Same readability; difference is **runtime**—deterministic sandbox + persisted history.

Minimal Temporal-style sketch (recognize in interview):

```ts
// Activities: retries, timeouts, side effects
// Workflow: deterministic branching + await activities
async function orderWorkflow(input: Order) {
  const pay = await processPayment(input);
  if (!pay.success) return fail("payment");
  const inv = await reserveInventory(input);
  if (!inv.success) {
    await refundPayment(input);
    return fail("inventory");
  }
  await shipOrder(input);
  await sendConfirmationEmail(input);
}
```

---

## Managed workflows (e.g. Step Functions) — one-liner

Same durability story: **checkpoint**, **resume**, integrate with cloud tasks. **Tradeoffs:** workflow JSON can get large/awkward; **AWS** example limits (e.g. state size, max duration—verify current docs in a real design). Good when team wants **ops-light** orchestration on that cloud.

---

## Picking a tool (interview defaults)

| Option | Strength | Weakness |
|--------|----------|----------|
| **Temporal** | Strong model, long-running, signals, open source | Operate Temporal in prod |
| **AWS Step Functions** | Serverless, AWS integration, visualization | Less expressive; cloud limits |
| **Azure Durable Functions / GCP Workflows** | Cloud-native, easier ops than self-host | Less flexible than Temporal for some cases |
| **Airflow** | Scheduled **batch** / ETL DAGs | Not the default for **event-driven user** workflows |

**Interview tip:** Default to **Temporal** unless the prompt is clearly **AWS-only** and simple—then **Step Functions** is a credible answer.

---

## When to propose workflows (signals in the prompt)

- Phrases like: “if step X fails, **undo** Y,” “**all or nothing**,” “wait for **human**,” “runs **days**,” “**audit** every step.”
- Domains: **payments**, **e-commerce fulfillment**, **Uber-style** matching (human in the loop), **loan/compliance** pipelines.

## When not to (show judgment)

| Situation | Prefer |
|-----------|--------|
| One async job (resize image, send email) | **Queue** + worker |
| Client waits; tight latency | **Sync** path or async **poll**—not a multi-day workflow |
| Millions of trivial ops | Workflow **overhead** may not pay |
| Plain CRUD | Don’t force a workflow |

**Line:** “I’d start with the simplest thing that meets SLOs and add a workflow engine when **state**, **compensation**, and **long-running** coordination show up.”

---

## Deep dives — Q&A cheat sheet

### “How do you change a workflow with thousands of in-flight runs?”

- **Versioning:** New workflows use v2; old runs finish on v1. Simple; bad if every open run **must** pick up a new legal step immediately.
- **Migrations / patches:** Update definition or use **deterministic branches** (e.g. `patched("feature-x")`) so replay stays consistent—old executions that already passed a point keep **legacy** behavior; new paths see new behavior.

### “History/state grows forever?”

- Store **IDs**, not huge payloads, in activity inputs/outputs.
- **Continue-as-new** / periodic **reset**: new run with compacted inputs for long-lived processes.

### “Wait days for a signature / webhook?”

- **Signals** + timeouts; reminders as activities; **no busy polling**. External systems call the workflow service API to deliver the signal.

### “Exactly once?”

- Engines often give **at-least-once** for activities. If activity **succeeds** but **ack fails**, **retry** can duplicate.
- **Fix:** **Idempotent** activities—**idempotency keys**, DB “already processed” rows before irreversible actions (charge, refund, email).

---

## Closing lines (use in wrap-up)

- Workflow engines fit **hairy state machines** where hand-rolled sagas and Redis state machines become **fragile**.
- You buy **centralized** retries, history, recovery, and clearer business code; you pay **ops** and **learning curve**.
- Recognize when you’re **rebuilding** an engine by hand—that’s the cue to name **Temporal / Step Functions / cloud workflow** and the tradeoffs above.

---

## Quick self-check (before the interview)

- [ ] Explain **workflow vs activity** and why workflow code is deterministic.
- [ ] Explain **replay** and why activities must be **idempotent**.
- [ ] Give **one** compensation example (payment + inventory).
- [ ] Answer **versioning** and **history size** in two sentences each.
- [ ] Name **one** case where a **queue** beats a workflow.
