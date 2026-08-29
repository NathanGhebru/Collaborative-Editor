# Batched Operation Log with Periodic Snapshots

**Status:** Accepted
**Date:** 2026-08-28
**Decision owner:** Codex
**Reviewer:** Antigravity
**Scope:** Durability boundary, operation batching, snapshots, recovery, autosave, and version-history behavior. Exact schema belongs in `docs/DATABASE.md`.

---

## 1. Context

Collaborative editing may generate many operations per second.

A naive persistence model could write the complete document after every keystroke.

That would cause:

* excessive PostgreSQL writes,
* unnecessary document rewrites,
* increased transaction overhead,
* higher synchronization latency,
* poor scalability.

At the same time, the application must provide:

* autosave,
* restart recovery,
* version history,
* operation idempotency,
* document-leader recovery,
* durable acknowledgements.

## 1.1 Problem

The system must make every acknowledged edit recoverable without paying for a full-document rewrite or a separate PostgreSQL transaction on every logical operation. It also needs bounded recovery time, idempotent retries, and exact historical checkpoints.

---

# 2. Decision

The application will persist collaborative document content using:

> **A batched append-only canonical operation log plus periodic full-document snapshots.**

Durable state is reconstructed from:

```text
latest valid snapshot
+
canonical operation batches after that snapshot
```

PostgreSQL is the durable source of truth.

Every new document is initialized with a full snapshot at revision `0`. That snapshot contains the optional initial text (or the empty string) and becomes the document's initial recovery base.

---

# 3. Persistence Path

Normal editing:

```text
OT operation validated and provisionally ordered
        ↓
canonical revision assigned
        ↓
short in-memory persistence buffer
        ↓
operation batch
        ↓
PostgreSQL transaction
        ↓
commit
        ↓
Redis propagation
        ↓
client acknowledgement
```

---

# 4. Durability Boundary

An operation is considered durably saved only after:

```text
PostgreSQL COMMIT
```

The frontend may show:

```text
Saved
```

only after all of the user's relevant local operations have received durable acceptance.

---

# 5. Alternatives Considered

## 5.1 Rewrite Full Document Per Edit

### Advantages

* simple mental model,
* easy document retrieval.

### Disadvantages

* very high write amplification,
* full text rewrite for tiny changes,
* poor high-frequency performance,
* loses useful operation history.

Rejected.

---

## 5.2 Store Every Operation as Its Own Row/Transaction

### Advantages

* explicit history,
* straightforward debugging.

### Disadvantages

* high transaction count,
* excessive index overhead,
* poor write efficiency at 1K+ ops/sec.

Rejected as the normal persistence path.

Logical operations remain individually represented inside batches.

---

## 5.3 Redis as Primary Document Store

### Advantages

* low latency.

### Disadvantages

* inappropriate durable source of truth for this architecture,
* complicates recovery,
* Redis Pub/Sub is not durable history,
* weaker persistence guarantees.

Rejected.

---

## 5.4 Periodic Full Document Saves Only

### Advantages

* fewer writes.

### Disadvantages

* edits between snapshots could be lost,
* reconnect/history becomes harder,
* insufficient canonical operation record.

Rejected.

---

# 6. Operation Batching

Validated and provisionally ordered operations are accumulated into very short-lived batches. They become durably accepted together only when the batch transaction commits.

Initial configuration target:

```text
maximum batch delay: 5–10 ms
maximum logical operations: 64
```

The batch flushes when either threshold is reached.

These values are starting configuration, not frozen performance settings.

---

# 7. Why Short Batching

The intended tradeoff is:

```text
reduce database transactions substantially
without
adding noticeable synchronization latency
```

The benchmark suite determines the final threshold.

---

# 8. Batch Contents

Each batch stores:

```text
documentId
syncEpoch
firstRevision
lastRevision
operationCount
canonical operations
createdAt
optional resulting content hash
```

Only transformed canonical operations are used for recovery.

---

# 9. Atomic Commit

A batch transaction atomically performs:

1. insert operation batch,
2. insert operation-id mappings,
3. advance `documents.current_revision`,
4. update document modification time.

All succeed or none succeed.

---

# 10. Conditional Revision Update

Suppose the leader believes:

```text
currentRevision = 500
```

and persists revisions:

```text
501–520
```

The document update must require:

```text
stored currentRevision == 500
```

If not:

```text
transaction fails
```

and the leader enters recovery.

This provides an additional database-level sequencer safety check.

---

# 11. Acknowledgement Ordering

Correct ordering:

```text
transform
↓
batch
↓
database commit
↓
publish accepted event
↓
acknowledge client
```

Incorrect ordering:

```text
acknowledge client
↓
database write fails
```

is forbidden.

---

# 12. Snapshot Decision

The system periodically materializes the complete document.

A snapshot stores:

```text
documentId
syncEpoch
revision
complete content
content hash
creation reason
timestamp
```

---

# 13. Snapshot Triggers

Initial triggers:

```text
500 revisions
OR
30 seconds while dirty
```

Additional triggers:

```text
manual historical version
pre-restore
controlled leader shutdown when useful
administrative maintenance
```

Thresholds are configurable.

---

# 14. Why Snapshots

Without snapshots, a document at revision:

```text
1,000,000
```

could require replaying one million logical operations when a new leader starts.

Snapshots bound recovery cost.

---

# 15. Recovery Algorithm

```text
find latest snapshot for current epoch
              ↓
validate snapshot
              ↓
load content
              ↓
read all later operation batches
              ↓
apply operations in revision order
              ↓
verify final revision
              ↓
room becomes active
```

---

# 16. Recovery Validation

After reconstruction verify:

```text
recoveredRevision == documents.current_revision
```

If not:

```text
room must not accept edits
```

until recovery succeeds.

Optional content hashes can detect corruption or implementation bugs.

---

# 17. Operation Idempotency

Logical operation identity:

```text
documentId
syncEpoch
clientId
clientOperationId
```

is stored separately in an indexed table.

Reason:

> Deduplication must not depend on scanning JSONB batch contents.

---

# 18. Duplicate Retry

When a duplicate operation is received:

```text
lookup operation identity
        ↓
existing?
   ┌────┴────┐
  yes        no
   │          │
return      process
original    normally
revision
```

The document is not modified twice.

---

# 19. Historical Versions

User-visible versions reference durable snapshots.

A version records:

```text
versionNumber
snapshotId
sourceEpoch
sourceRevision
createdBy
reason
label
timestamp
```

---

# 20. Version Creation

When a version is created:

1. ensure document state through revision R is committed,
2. materialize or reuse a snapshot at R,
3. insert `document_versions`.

The version therefore references exact recoverable content.

---

# 21. Periodic Versions

Historical versions need not match every OT operation.

Automatic versions may be created less frequently.

Example policies may eventually use:

```text
time interval
large edit threshold
user inactivity
manual creation
```

The product only requires useful history, not one visible version per keystroke.

---

# 22. Restore Strategy

Restoring an old version does not delete newer history.

Instead:

```text
save current state
       ↓
create new sync epoch
       ↓
materialize restored content
       ↓
new revision = 0
```

Old epoch data remains historical.

---

# 23. Pre-Restore Protection

Before a restore:

* verify caller authorization,
* verify expected current epoch/revision,
* create a pre-restore version,
* preserve current document contents.

The restore is reversible through history.

---

# 24. Restore Transaction

Restore must atomically:

```text
create required snapshot/version metadata
+
change document syncEpoch
+
set revision 0
+
create the new epoch's revision-0 snapshot containing the restored text
```

Only after transaction commit is:

```text
DOCUMENT_RESET
```

published.

---

# 25. Autosave Definition

Autosave is not a timer that occasionally sends the entire document.

For this project:

> **Autosave means accepted collaborative operations are continuously persisted through the batched operation-log pipeline.**

The user normally never presses Save.

---

# 26. Database Write Reduction Goal

The project has a target of approximately:

```text
60% fewer PostgreSQL write transactions
```

compared with:

```text
one transaction per logical edit operation
```

The actual result must be benchmarked.

If batching reduces transactions by another amount, only the measured result may be reported.

---

# 27. Measurement

Benchmark:

```text
same operation workload
same clients
same document
same infrastructure
```

Compare:

```text
baseline:
per-operation transaction

optimized:
batched operation persistence
```

Measure:

```text
transactions/sec
operations/sec
p95 sync latency
DB CPU
DB latency
error rate
```

---

# 28. Failure Before Commit

If an application instance dies before batch commit:

```text
operations remain unacknowledged
```

Clients retry after reconnect.

No accepted durable history was promised.

---

# 29. Failure After Commit but Before Acknowledgement

If PostgreSQL commits but the server dies before acknowledgment:

```text
client retries same operation ID
```

The new leader detects existing operation identity and returns the original canonical revision.

No duplicate edit occurs.

---

# 30. Failure After Commit but Before Redis Publish

A remote server may temporarily miss the operation.

Revision-gap detection identifies the missing canonical revision.

The missing operation is fetched from PostgreSQL.

---

# 31. Snapshot Failure

Failure to create a periodic snapshot does not invalidate already committed operation history.

The system may:

* retry later,
* continue temporarily using the previous snapshot.

It must emit an operational warning.

---

# 32. Version Creation Failure

Failure to create a user-visible version returns an error.

It does not roll back already committed document edits.

---

# 33. Data Retention

Initial portfolio-project policy:

> Retain operation history indefinitely.

Advantages:

* simpler recovery,
* easier debugging,
* benchmark analysis,
* complete historical evidence.

Long-term compaction is deferred.

---

# 34. Future Compaction

If storage becomes significant, a future ADR may allow:

```text
archive old operation batches
delete operations before protected snapshots
compress old batches
```

only when retained versions remain reconstructable.

---

# 35. PostgreSQL Schema Authority

Exact tables and indexes are defined by:

```text
docs/DATABASE.md
```

ADR-003 defines behavior, not every SQL column.

---

# 36. Testing

Required persistence tests:

```text
operation batch commit
atomic revision update
duplicate operation
failure before commit
failure after commit
snapshot recovery
snapshot + operation replay
10K revisions
version creation
version restore
old epoch preservation
database restart
application restart
```

---

# 37. Performance Testing

Persistence benchmarks must track:

```text
average batch size
p95 batch size
batch flush frequency
commit latency
transactions/sec
operations/sec
DB CPU
connection-pool utilization
snapshot duration
snapshot size
```

---

# 38. Consequences

## Positive

* fewer PostgreSQL transactions,
* canonical history remains explicit,
* efficient recovery,
* version history integrates naturally,
* idempotent reconnect is straightforward,
* Redis loss does not destroy documents.

## Negative

* recovery logic is more involved,
* snapshots must be maintained,
* operation batches require serialization,
* batch timing affects synchronization latency,
* database transaction logic must be precise.

---

# 39. Frozen Decisions

ADR-003 freezes:

1. PostgreSQL as durable source of truth.
2. Canonical operation history as the primary incremental persistence representation.
3. Short batching before persistence.
4. Atomic batch commits.
5. Client acknowledgement after durable commit.
6. Periodic full-document snapshots.
7. Version history backed by snapshots.
8. Version restore through a new synchronization epoch.
9. Indexed operation-id deduplication.
10. Redis not being the sole durable document store.

---

# 40. Superseding This ADR

A material change to:

```text
event sourcing model
snapshot strategy
durability boundary
primary database
acknowledgement-before-persistence semantics
```

requires a new ADR.

---
