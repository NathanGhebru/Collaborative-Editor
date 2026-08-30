# Database and Persistence Contract

## Status

**Status:** User, authentication, document, and canonical operation persistence schemas frozen (AUTH-001, DOC-001, PERS-001)
**Primary durable database:** PostgreSQL
**Distributed runtime store:** Redis

This document defines durable application data and Redis runtime responsibilities.

PostgreSQL is the durable source of truth.

Redis must not become the only permanent copy of document state.

---

# 1. Persistence Goals

The persistence system must support:

* users
* authentication sessions
* documents
* document ownership
* sharing permissions
* OT operation history
* snapshots
* version history
* recovery after application restart
* recovery after document-leader failure
* efficient batched writes
* at least 10,000 logical document revisions in testing

The design should also support a target reduction in database write transactions through batching.

---

# 2. Database Technology

Primary database:

```text
PostgreSQL
```

Recommended identifier type:

```text
UUID
```

Recommended timestamp type:

```text
TIMESTAMPTZ
```

All timestamps are stored in UTC.

---

# 3. Schema Overview

```text
users
  │
  ├── refresh_tokens
  │
  ├── documents ───────────────┐
  │       │                    │
  │       ├── document_permissions
  │       │
  │       ├── document_operation_batches
  │       ├── document_operation_ids
  │       │
  │       ├── document_snapshots
  │       │
  │       └── document_versions
  │
  └────────────────────────────┘
```

---

# 4. `users` (Frozen - AUTH-001)

Stores application users.

```sql
users
-----
id                  UUID PRIMARY KEY
username            VARCHAR(32) NOT NULL UNIQUE
email               VARCHAR(255) NOT NULL UNIQUE
password_hash       VARCHAR(72) NOT NULL
display_name        VARCHAR(64) NOT NULL
account_status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
```

Status values:

```text
ACTIVE
DISABLED
```

Usernames and emails are stored in lower-case with unique index constraints.

Passphrase algorithm: BCrypt with cost factor 10 (producing a 60-72 byte string). Passwords are never stored directly.

---

# 5. `refresh_tokens` (Frozen - AUTH-001)

Stores refresh-token verifiers.

```sql
refresh_tokens
--------------
id                  UUID PRIMARY KEY
user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE
token_hash          VARCHAR(64) NOT NULL UNIQUE
expires_at          TIMESTAMPTZ NOT NULL
revoked_at          TIMESTAMPTZ NULL
replaced_by_id      UUID NULL REFERENCES refresh_tokens(id)
created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
last_used_at        TIMESTAMPTZ NULL
user_agent          VARCHAR(512) NULL
ip_metadata         VARCHAR(45) NULL
```

The raw 256-bit refresh token is sent to the browser in an `HttpOnly` cookie and never stored directly in the database. `token_hash` stores the hex-encoded SHA-256 hash of the token string.

Indexes:

```text
idx_refresh_tokens_user_id ON refresh_tokens(user_id)
idx_refresh_tokens_token_hash UNIQUE ON refresh_tokens(token_hash)
idx_refresh_tokens_expires_at ON refresh_tokens(expires_at)
```

Migration Tool: Flyway migrations located at `backend/src/main/resources/db/migration/V1__init_auth_schema.sql`.

Expired and revoked records may be periodically purged via a scheduled cleanup query.

---

# 6. `documents` (Frozen - DOC-001)

Stores durable document metadata and current synchronization identity.

```sql
documents
---------
id                  UUID PRIMARY KEY
owner_id            UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT
title               VARCHAR(255) NOT NULL
sync_epoch          UUID NOT NULL
current_revision    BIGINT NOT NULL DEFAULT 0
created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
```

Initial state upon creation:
- `current_revision` = 0
- `sync_epoch` = newly generated UUID v4
- `title` = trimmed string (1 to 255 chars)

Constraints & Rules:
- `owner_id`: Foreign key to `users(id)` with `ON DELETE RESTRICT`. Users cannot be hard-deleted while owning active documents.
- `title`: `VARCHAR(255)` constraint. Non-empty string.

Indexes:
```text
idx_documents_owner_id ON documents(owner_id)
idx_documents_updated_at_id ON documents(updated_at DESC, id DESC)
```

Creation Transaction:
Creating a document atomically inserts both the `documents` row and an initial revision-0 snapshot in `document_snapshots` within a single database transaction.

---

# 7. Document Content & Initial Revision-0 Snapshots (Frozen - DOC-001)

Canonical recoverable content is represented by:

```text
latest durable snapshot
+
later committed operation batches
```

To support initial content persistence and full snapshot recovery, snapshots are stored in `document_snapshots`:

```sql
document_snapshots
------------------
id                  UUID PRIMARY KEY
document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE
sync_epoch          UUID NOT NULL
revision            BIGINT NOT NULL
content             TEXT NOT NULL
content_hash        VARCHAR(64) NOT NULL
created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP

CONSTRAINT uk_document_snapshots_doc_rev UNIQUE (document_id, revision)
```

Rules:
- When a document is created with initial text (or empty string `""`), a snapshot with `revision = 0`, `sync_epoch = <document_sync_epoch>`, `content = <initialContent>`, and `content_hash = SHA256(content)` is inserted atomically.
- `ON DELETE CASCADE`: Deleting a document hard-deletes all associated snapshots.

Indexes:
```text
idx_document_snapshots_doc_rev UNIQUE ON document_snapshots(document_id, revision)
```

---

# 8. `document_permissions` (Frozen - DOC-001)

Stores non-owner document access rights.

```sql
document_permissions
--------------------
id                  UUID PRIMARY KEY
document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE
user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE
role                VARCHAR(32) NOT NULL DEFAULT 'EDITOR'
granted_by          UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT
created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP

CONSTRAINT uk_document_permissions_doc_user UNIQUE (document_id, user_id),
CONSTRAINT chk_document_permissions_role CHECK (role IN ('EDITOR'))
```

Rules:
- Initial supported role: `'EDITOR'`.
- The document owner does not require a row in `document_permissions`; ownership is defined by `documents.owner_id`.
- `ON DELETE CASCADE`: Deleting a document hard-deletes all associated permission rows. Deleting a user revokes their granted permissions.

Indexes:
```text
idx_document_permissions_user_id ON document_permissions(user_id)
idx_document_permissions_doc_user UNIQUE ON document_permissions(document_id, user_id)
```

Migration Tool:
Flyway migrations located at `backend/src/main/resources/db/migration/V2__init_document_schema.sql`.

---

# 9. `document_operation_batches`

Stores committed OT operations.

```sql
document_operation_batches
--------------------------
id                  UUID PRIMARY KEY
document_id         UUID NOT NULL REFERENCES documents(id)
sync_epoch          UUID NOT NULL
first_revision      BIGINT NOT NULL
last_revision       BIGINT NOT NULL
operations          JSONB NOT NULL
operation_count     INTEGER NOT NULL
content_hash_after  VARCHAR(...) NULL
created_at          TIMESTAMPTZ NOT NULL
```

Constraint:

```text
first_revision <= last_revision
operation_count > 0
```

The number of represented revisions must agree with batch contents.

---

# 10. Operation Batch JSON

Example:

```json
{
  "operations": [
    {
      "revision": 401,
      "clientId": "7a719fbf-87ce-408d-beac-c665df880eaf",
      "clientOperationId": "b9163582-3385-4674-9be4-cb350ae7ab5e",
      "actorUserId": "9cd819ab-20de-4356-8870-69757480c0d1",
      "kind": "INSERT",
      "position": 10,
      "text": "hello"
    },
    {
      "revision": 402,
      "clientId": "0f539e30-9935-4ecb-92b4-b9596858204b",
      "clientOperationId": "fa33649f-d635-4349-9093-df85688d57a1",
      "actorUserId": "7ab5e5a4-ab19-41fd-bac2-4ddfbc88ac31",
      "kind": "DELETE",
      "position": 15,
      "length": 2
    }
  ]
}
```

Only canonical transformed operations are stored.

The original untransformed client request may be logged separately for debugging if needed, but canonical recovery depends on the accepted operation.

---

# 11. Operation Identity Index

Duplicate client operations must be detectable.

Because operations are nested in a batch JSON structure, idempotency lookup should not depend solely on searching JSONB.

Use an additional mechanism.

Preferred design:

```sql
document_operation_ids
----------------------
document_id         UUID NOT NULL
sync_epoch          UUID NOT NULL
client_id           UUID NOT NULL
client_operation_id UUID NOT NULL
revision            BIGINT NOT NULL
batch_id            UUID NOT NULL
created_at          TIMESTAMPTZ NOT NULL

PRIMARY KEY (
    document_id,
    sync_epoch,
    client_id,
    client_operation_id
)
```

Foreign keys:

```text
document_id → documents(id) ON DELETE CASCADE
batch_id → document_operation_batches(id) ON DELETE CASCADE
```

This provides efficient idempotency checks.

A retry can map directly to its previously assigned revision.

---

# 12. Operation Batch Indexes

Recommended:

```text
(document_id, sync_epoch, first_revision)
(document_id, sync_epoch, last_revision)
```

Both indexes are unique for protocol v1. Together with the conditional `documents.current_revision` update and transaction validation, they prevent duplicate batch boundaries; the service also rejects overlapping or noncontiguous ranges.

Queries must efficiently retrieve:

```text
all operations after revision R
```

for one document epoch.

---

# 13. Revision Integrity

Within one document and epoch:

```text
1
2
3
...
N
```

must be gap-free in durable canonical history.

A persistence transaction must not commit:

```text
revision 102
```

without the preceding canonical history required by the document.

---

# 14. Persistence Transaction

A normal operation-batch commit should atomically:

1. insert `document_operation_batches`,
2. insert idempotency rows,
3. update `documents.current_revision`,
4. update `documents.updated_at`.

Conceptually:

```sql
BEGIN;

INSERT operation batch;

INSERT operation IDs;

UPDATE documents
SET current_revision = :lastRevision,
    updated_at = NOW()
WHERE id = :documentId
  AND sync_epoch = :expectedEpoch
  AND current_revision = :expectedPreviousRevision;

COMMIT;
```

The conditional document update protects against unexpected competing sequencers.

A zero-row update indicates an architecture invariant violation or stale leader.

---

# 15. Database Fencing

Redis leadership determines normal document sequencing, but PostgreSQL provides an additional safety check.

A leader attempting to commit:

```text
previousRevision = 100
new batch = 101–110
```

must only succeed if PostgreSQL still records:

```text
currentRevision = 100
```

If PostgreSQL records something else, the leader must stop and recover rather than overwrite history.

---

# 16. `document_snapshots`

Stores complete materialized document state.

```sql
document_snapshots
------------------
id                  UUID PRIMARY KEY
document_id         UUID NOT NULL REFERENCES documents(id)
sync_epoch          UUID NOT NULL
revision            BIGINT NOT NULL
content             TEXT NOT NULL
content_hash        VARCHAR(...) NOT NULL
reason              VARCHAR(...) NOT NULL
created_at          TIMESTAMPTZ NOT NULL
```

Possible reasons:

```text
PERIODIC
LEADER_HANDOFF
VERSION
PRE_RESTORE
SYSTEM
```

Unique constraint:

```text
(document_id, sync_epoch, revision)
```

where appropriate.

---

# 17. Snapshot Integrity

A snapshot represents document state after applying all canonical operations through:

```text
revision N
```

within its epoch.

Its hash should be generated from a canonical byte representation of the content.

Example:

```text
SHA-256
```

The exact algorithm may be configured but must remain stable for persisted rows.

---

# 18. Snapshot Creation

The active leader may create a snapshot:

```text
every N operations
every T seconds while dirty
before controlled shutdown
when creating a historical version
before version restoration
```

Initial configuration may use:

```text
500 operations
or
30 seconds
```

subject to benchmarking.

---

# 19. Recovery Algorithm

To reconstruct a document:

```text
SELECT latest compatible snapshot
            ↓
load snapshot content
            ↓
SELECT operation batches
where revision > snapshot.revision
            ↓
apply operations in revision order
            ↓
verify resulting revision
            ↓
document ready
```

If no snapshot exists:

```text
start from initial document state
+
replay operation history
```

---

# 20. `document_versions`

Stores user-visible historical checkpoints.

```sql
document_versions
-----------------
id                  UUID PRIMARY KEY
document_id         UUID NOT NULL REFERENCES documents(id)
version_number      BIGINT NOT NULL
snapshot_id         UUID NOT NULL REFERENCES document_snapshots(id)
source_epoch        UUID NOT NULL
source_revision     BIGINT NOT NULL
created_by          UUID NULL REFERENCES users(id)
reason              VARCHAR(...) NOT NULL
label               VARCHAR(...) NULL
created_at          TIMESTAMPTZ NOT NULL
```

Unique constraint:

```text
(document_id, version_number)
```

---

# 21. Version Numbers

Version numbers increase independently from OT revisions.

Example:

```text
Document revision: 9,421

Historical versions:
Version 1
Version 2
...
Version 38
```

A version points to a snapshot representing a particular document state.

---

# 22. Automatic Version Creation

Periodic historical versions may be created based on:

```text
time
number of edits
explicit user action
important lifecycle event
```

Version creation frequency should be lower than OT operation frequency.

The application does not need one user-visible version per keystroke.

---

# 23. Version Restore

Restoring a version must occur inside a controlled transaction.

Conceptually:

```text
current document:
epoch A
revision 921

restore historical version
        ↓
save pre-restore snapshot/version
        ↓
new epoch B
        ↓
revision reset to 0
        ↓
historical content becomes snapshot revision 0
```

The document row becomes:

```text
sync_epoch = B
current_revision = 0
```

Old operation history remains associated with epoch A for historical purposes.

---

# 24. Restore Transaction

Conceptually:

```sql
BEGIN;

verify expected current epoch/revision;

create pre-restore snapshot if required;

create pre-restore version;

generate new sync epoch;

create revision-0 snapshot with historical content;

UPDATE documents
SET sync_epoch = :newEpoch,
    current_revision = 0,
    updated_at = NOW();

COMMIT;
```

Only after commit does the backend publish:

```text
DOCUMENT_RESET
```

to connected clients.

---

# 25. Document Deletion

Protocol v1 uses hard deletion with controlled cascading for portfolio simplicity.

Deleting a document should remove:

```text
permissions
operation IDs
operation batches
versions
snapshots
```

Foreign keys should make orphaned collaboration history impossible.

A future production design may prefer soft deletion and retention policy.

---

# 26. Cascade Direction

Recommended relationships:

```text
documents
  ├── permissions            ON DELETE CASCADE
  ├── operation_ids          ON DELETE CASCADE
  ├── operation_batches      ON DELETE CASCADE
  ├── snapshots              ON DELETE CASCADE
  └── versions               ON DELETE CASCADE
```

The document row does not keep a child snapshot foreign-key pointer. The latest valid snapshot is selected by `(document_id, sync_epoch, revision)` ordering, avoiding a circular document/snapshot relationship.

---

# 27. Redis Responsibilities

Redis stores only runtime/distributed coordination data.

Conceptual keys include:

```text
collab:leader:{documentId}

realtime:ticket:{ticketId}

presence:connection:{connectionId}

presence:document:{documentId}
```

Conceptual channels include:

```text
collab:ingress:{documentId}
collab:events:{documentId}
presence:events:{documentId}
cursor:events:{documentId}
```

Exact naming is finalized in:

```text
ADR-004-redis-architecture.md
```

---

# 28. Document Leader Key

Conceptually:

```text
collab:leader:{documentId}
```

Value:

```json
{
  "instanceId": "backend-a",
  "leaseId": "e0d8aa8f-...",
  "epoch": "a165202b-..."
}
```

The Redis key must expire automatically if not renewed.

Leadership must use safe compare-and-renew / compare-and-release behavior.

An old owner must not be able to delete a newer owner's lease.

---

# 29. Real-Time Tickets

Conceptual Redis entry:

```text
realtime:ticket:{ticketHash}
```

Value:

```json
{
  "userId": "...",
  "documentId": "...",
  "permission": "EDITOR"
}
```

TTL:

```text
30–60 seconds
```

The ticket is deleted atomically on successful consumption.

Raw tickets should not be logged.

---

# 30. Presence Storage

Presence is ephemeral.

Possible model:

```text
presence:connection:{connectionId}
```

with TTL.

Value contains:

```text
documentId
userId
instanceId
displayName
lastSeen
```

A document-level set may provide efficient discovery:

```text
presence:document:{documentId}
```

Stale entries must be removable even if a server crashes.

---

# 31. Cursor Storage

Cursor positions generally do not need durable Redis storage.

They may be propagated using Pub/Sub only.

Late joiners do not require historically accurate cursor movement.

After joining, clients receive new cursor updates.

---

# 32. Redis Failure

Redis failure may temporarily prevent:

* leader coordination
* cross-instance edits
* presence
* real-time ticket creation

The application must not silently allow multiple sequencers to continue if distributed leadership cannot be established safely.

Possible behavior:

```text
existing leader enters bounded degraded mode
or
document editing pauses
```

depending on the final leader-fencing ADR.

Document durability remains in PostgreSQL.

---

# 33. PostgreSQL Failure

If PostgreSQL becomes unavailable:

* new committed operations cannot receive durable success acknowledgement,
* version creation fails,
* document creation fails,
* durable metadata updates fail.

The application may temporarily retain local pending work but must not falsely report it as saved.

---

# 34. Database Connection Pool

Spring Boot should use a bounded PostgreSQL connection pool.

Pool size must be tuned based on:

```text
backend instances
RDS connection limit
request concurrency
persistence batch behavior
```

Increasing the pool indefinitely is not a valid performance strategy.

---

# 35. Database Index Summary

At minimum:

```text
users(username) UNIQUE
users(email) UNIQUE

refresh_tokens(token_hash) UNIQUE
refresh_tokens(user_id)

documents(owner_id)
documents(updated_at)

document_permissions(user_id)
document_permissions(document_id, user_id) PRIMARY KEY

operation_batches(document_id, sync_epoch, first_revision)
operation_batches(document_id, sync_epoch, last_revision)

operation_ids(
    document_id,
    sync_epoch,
    client_id,
    client_operation_id
) PRIMARY KEY

snapshots(document_id, sync_epoch, revision)

versions(document_id, version_number) UNIQUE
versions(document_id, created_at)
```

Indexes must be verified with actual query plans before performance claims.

---

# 36. Migration System

All schema changes must use version-controlled migrations.

Recommended:

```text
Flyway
```

or another architecture-approved migration tool.

Application startup must not depend on manually editing the database.

Migration files belong in backend source control.

---

# 37. Schema Change Policy

Breaking persistence changes require:

* migration
* compatibility consideration
* rollback or recovery plan where relevant
* integration tests

Agents must not manually modify local PostgreSQL and treat the change as project implementation.

---

# 38. Test Database

Integration tests should use isolated PostgreSQL instances, preferably through:

```text
Testcontainers
```

or Docker-based equivalents.

Persistence tests must verify real PostgreSQL behavior rather than relying only on an in-memory substitute.

---

# 39. Redis Integration Tests

Redis behavior should also use a real Redis test instance where practical.

Tests should cover:

```text
leader acquisition
lease expiration
ticket consumption
presence TTL
Pub/Sub propagation
duplicate messages
backend restart
```

---

# 40. Persistence Tests

Required tests include:

```text
create document
persist operation batch
recover from snapshot + operations
duplicate operation ID
wrong previous revision
snapshot creation
version creation
restore version
new epoch after restore
document deletion cascade
permission deletion
backend restart recovery
10K+ logical revisions
```

---

# 41. Database Write Optimization

A naive implementation might perform:

```text
one transaction
per
keystroke
```

The project instead uses:

```text
short operation batching
+
batch insertion
+
periodic snapshots
```

A performance target is approximately:

```text
60% fewer database write transactions
```

than the naive baseline.

The metric must be measured.

A batch must not delay operations enough to violate synchronization-latency goals.

---

# 42. Data Durability Boundary

An operation is considered durably accepted only after the transaction containing it commits.

The normal ordering is:

```text
transform
   ↓
batch
   ↓
PostgreSQL commit
   ↓
accepted event
   ↓
client acknowledgement
```

This gives a clear meaning to:

```text
Saved
```

in the frontend.

---

# 43. Current Revision Durability

`documents.current_revision` always reflects the last committed canonical revision.

It must never intentionally point beyond durable operation history.

---

# 44. Data Retention

For the project version, old operation history may be retained indefinitely to simplify version recovery and benchmarking.

Future production retention may compact or archive old operation batches.

Compaction must not destroy document versions that still depend on the history.

---

# 45. Security

Sensitive database fields include:

```text
password hashes
refresh-token hashes
email addresses
authentication metadata
```

Access must be limited to required backend services.

Database credentials must come from environment/secrets management, never source code.

---

# 46. Backups

Production deployment should enable managed PostgreSQL backups.

The final AWS architecture should include:

* automated backups,
* point-in-time recovery where practical,
* restore testing.

A backup that has never been tested for restoration should not be treated as verified recovery.

---

# 47. Database Observability

Metrics should include:

```text
connection-pool utilization
query latency
batch persistence latency
batch size
snapshot latency
version-creation latency
failed transactions
deadlocks
database CPU
database storage growth
```

Slow-query analysis should be performed before adding speculative indexes.

---

# 48. Persistence Invariants

The database layer must preserve:

### Invariant 1

Every committed operation belongs to exactly one document epoch.

### Invariant 2

Canonical revisions within an epoch are ordered and recoverable.

### Invariant 3

A client operation identity is applied at most once.

### Invariant 4

`documents.current_revision` never exceeds committed history.

### Invariant 5

A snapshot identifies the exact epoch/revision it represents.

### Invariant 6

A historical version references reconstructable state.

### Invariant 7

Version restore creates a new epoch rather than rewriting old history.

### Invariant 8

Redis loss alone cannot erase committed document contents.

### Invariant 9

Deleting a document does not leave unauthorized orphan resources.

### Invariant 10

An acknowledgement marked durable corresponds to committed PostgreSQL state.

---

# 49. Open Schema Decisions

Before each affected database migration is frozen, the database contract must replace its relevant placeholders and recommendations with exact choices for:

- string lengths and normalization rules for username, email, display name, title, and labels;
- check constraints or database enum strategy for account status, permission role, and reason fields;
- migration tool selection;
- canonical JSON schema and serialization version for operation batches;
- content-hash algorithm and canonical byte encoding;
- timestamp source and precision;
- retention/cleanup policy for revoked refresh-token rows;
- whether operation-batch range integrity needs a database exclusion constraint in addition to service and transaction checks.

These decisions block schema migration implementation, not repository bootstrap.

---

# 50. Persistence Summary

The durable document model is:

```text
Document metadata
      │
      ├── current epoch/revision
      │
      ├── snapshots
      │
      ├── operation batches
      │
      └── historical versions
```

The recovery path is:

```text
PostgreSQL snapshot
        ↓
later operation batches
        ↓
canonical document state
```

Redis provides:

```text
coordination
+
real-time propagation
+
ephemeral state
```

but PostgreSQL remains the durable authority.

The resulting persistence design supports both:

```text
high-frequency collaborative editing
```

and:

```text
recoverable historical document state
```

without requiring a full PostgreSQL document rewrite for every keystroke.
