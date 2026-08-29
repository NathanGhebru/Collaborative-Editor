# Redis Coordination and Cross-Instance Collaboration

**Status:** Accepted
**Date:** 2026-08-28
**Decision owner:** Codex
**Reviewer:** Antigravity
**Scope:** Redis ownership, leader coordination, cross-instance propagation, ephemeral state, and Redis failure behavior.

---

## 1. Context

The application must support horizontally scaled Spring Boot WebSocket servers.

A load balancer may connect users editing the same document to different backend instances.

Example:

```text
User A → Backend A
User B → Backend B
User C → Backend C
```

All three users must still participate in one consistent collaboration session.

The architecture therefore requires distributed:

* message propagation,
* document sequencing,
* presence,
* connection-ticket validation.

## 1.1 Problem

The backend must separate arbitrary WebSocket connection placement from the single canonical order required by OT, while keeping presence and fan-out fast and ensuring that loss of the coordination system cannot erase committed documents.

---

# 2. Decision

The project will use:

> **Redis for distributed coordination, ephemeral state, leader leases, and Pub/Sub message propagation.**

PostgreSQL remains the durable source of truth.

Redis is never the sole permanent record of canonical document history.

---

# 3. Redis Responsibilities

Redis owns runtime responsibilities including:

```text
document-leader leases
operation ingress routing
accepted-operation propagation
presence
cursor propagation
short-lived WebSocket tickets
selected ephemeral caches
```

---

# 4. Redis Does Not Own

Redis does not permanently own:

```text
users
documents
permissions
canonical operation history
snapshots
version history
```

Those belong in PostgreSQL.

---

# 5. Alternatives Considered

## 5.1 In-Memory State Per Spring Instance

### Advantages

* simple,
* extremely fast.

### Disadvantages

* users on separate instances cannot collaborate,
* server failure loses coordination,
* impossible horizontal scaling.

Rejected.

---

## 5.2 Sticky Sessions Only

All collaborators could be routed to the same backend.

### Advantages

* simpler room state.

### Disadvantages

* load-balancer affinity does not guarantee same document → same server,
* poor failure handling,
* scaling remains constrained,
* hides rather than solves distributed collaboration.

Rejected as the correctness strategy.

Sticky sessions may exist operationally but correctness cannot depend on them.

---

## 5.3 PostgreSQL LISTEN/NOTIFY

Possible cross-instance event transport.

### Advantages

* fewer infrastructure components,
* integrated with PostgreSQL.

### Disadvantages

* mixes high-frequency ephemeral messaging with primary persistence,
* less appropriate for presence/cursor load,
* Redis is already useful for ephemeral state and leader coordination.

Rejected.

---

## 5.4 Kafka

### Advantages

* durable event log,
* strong high-throughput messaging.

### Disadvantages

* substantially more infrastructure,
* unnecessary complexity for target scale,
* Redis already satisfies ephemeral propagation requirements,
* canonical durability already exists in PostgreSQL.

Rejected for this project.

---

# 6. Document Leader Model

Every actively edited document has at most one intended active:

```text
document leader
```

The leader is a Spring Boot instance.

The leader owns:

* canonical in-memory document state,
* OT transformation,
* revision assignment,
* persistence batching.

---

# 7. Why a Document Leader

OT requires canonical ordering.

Without a leader, two servers could simultaneously assign:

```text
revision 101
```

to different operations.

Centralizing sequencing per document eliminates that ambiguity.

---

# 8. Leader Lease Key

Conceptual Redis key:

```text
collab:leader:{documentId}
```

Value:

```json
{
  "instanceId": "backend-a",
  "leaseId": "uuid"
}
```

The key has a short TTL.

---

# 9. Acquiring Leadership

Leadership acquisition uses an atomic Redis operation conceptually equivalent to:

```text
SET key value NX PX leaseDuration
```

Only one instance can successfully acquire an absent lease.

---

# 10. Lease Duration

Initial target:

```text
lease duration: 10 seconds
renewal interval: 3 seconds
```

These values are configurable.

The leader should have multiple renewal opportunities before expiration.

---

# 11. Lease Renewal

Renewal must use:

```text
compare leaseId
then extend TTL
```

atomically.

A server must not extend a lease belonging to another instance.

Use a Redis Lua script or another atomic equivalent.

---

# 12. Lease Release

Release must also:

```text
compare leaseId
then delete
```

atomically.

An old leader must never delete a newly acquired lease.

---

# 13. Loss of Redis Connectivity

If a leader cannot prove continued lease ownership:

> **It must stop accepting new canonical operations.**

It may not assume leadership continues indefinitely.

This favors correctness over temporary availability.

---

# 14. Database Safety Fence

Redis leadership is reinforced by PostgreSQL's conditional revision update.

Suppose two servers unexpectedly attempt to commit from revision:

```text
100
```

Both transactions require:

```text
documents.current_revision = 100
```

Only one can successfully advance canonical history.

The loser must stop and recover.

This provides protection against lease race edge cases.

---

# 15. Operation Ingress Channel

Clients connect to arbitrary Spring instances.

A non-leader forwards operations through a conceptual Redis channel:

```text
collab:ingress:{documentId}
```

Message includes:

```text
documentId
syncEpoch
sourceInstanceId
sourceConnectionId
clientId
clientOperationId
baseRevision
operation
```

---

# 16. Leader Processing

The leader subscribes to operation ingress for documents it owns.

Flow:

```text
client
  ↓
connection-owning backend
  ↓
Redis ingress
  ↓
document leader
  ↓
OT
  ↓
PostgreSQL
```

Ingress uses non-durable Pub/Sub intentionally. If ingress is lost, the operation remains unacknowledged and the client retries with the same logical operation identity; the leader must never infer acceptance from ingress delivery alone.

---

# 17. Local-Leader Optimization

If the connection-owning server is also the leader:

```text
do not require an unnecessary Redis ingress round trip
```

The operation may enter the leader's local processing queue directly.

The accepted result still follows the normal cross-instance propagation path.

---

# 18. Accepted Event Channel

After persistence, canonical accepted events are published through:

```text
collab:events:{documentId}
```

Event includes:

```text
documentId
syncEpoch
revision
clientId
clientOperationId
actorUserId
canonical operation
```

---

# 19. Why Publish After Commit

Publishing before persistence could expose an operation to browsers that later disappears after a database failure.

Therefore:

```text
commit first
publish second
```

is mandatory.

---

# 20. Pub/Sub Is Not Durable

Redis Pub/Sub provides live delivery only.

A subscriber may miss events during:

```text
restart
network interruption
Redis interruption
subscription transition
```

Canonical revisions therefore permit gap detection.

---

# 21. Gap Detection

If an instance has processed:

```text
revision 100
```

and receives:

```text
revision 103
```

it detects missing:

```text
101
102
```

The instance retrieves missing canonical operations from PostgreSQL.

It then applies:

```text
101
102
103
```

in order.

---

# 22. Duplicate Accepted Events

If current local revision is:

```text
103
```

and event revision is:

```text
<= 103
```

the event is duplicate/stale.

Do not apply it again.

---

# 23. Subscription Strategy

A backend should subscribe only to document channels for rooms it currently needs rather than every document in the system.

This bounds event fan-out.

Conceptually:

```text
first local user joins document
→ subscribe

last local user leaves
→ eventually unsubscribe
```

Leaders may maintain required subscriptions independently.

---

# 24. Channel Naming

Canonical conceptual prefixes:

```text
collab:ingress:{documentId}
collab:events:{documentId}
presence:events:{documentId}
cursor:events:{documentId}
```

Internal protocol messages include an internal schema version.

---

# 25. Redis Cluster Compatibility

Redis channel/key design must avoid relying on cross-key atomic behavior unnecessarily.

Where atomic scripts are used, they should operate on the minimum required keys.

Deployment-specific cluster constraints should be covered during AWS integration.

---

# 26. Presence

Presence is ephemeral distributed state.

Conceptual connection key:

```text
presence:connection:{connectionId}
```

Value includes:

```text
documentId
userId
displayName
instanceId
lastSeen
```

The record has a TTL.

---

# 27. Presence Per Document

A document-level index may maintain active connection IDs:

```text
presence:document:{documentId}
```

The implementation must tolerate stale members because servers can crash.

Connection TTL remains the final liveness check.

---

# 28. User Presence Aggregation

Presence is fundamentally connection-based.

Example:

```text
User A
 ├── laptop tab
 └── desktop tab
```

The UI may display:

```text
User A — 2 connections
```

or collapse to one visible user.

---

# 29. Presence Expiration

Presence must recover from ungraceful server failure.

Therefore it cannot depend exclusively on explicit:

```text
LEFT
```

messages.

TTL/heartbeat expiration removes stale sessions.

---

# 30. Cursor Propagation

Cursor traffic uses:

```text
cursor:events:{documentId}
```

or equivalent multiplexing.

Cursor data does not require durable Redis storage.

---

# 31. Cursor Loss

Dropping a cursor event is acceptable.

The next cursor update replaces it.

Cursor/event loss must never trigger document operation recovery.

---

# 32. Realtime Tickets

Real-time ticket conceptual key:

```text
realtime:ticket:{ticketHash}
```

TTL:

```text
30–60 seconds
```

Ticket is consumed atomically.

---

# 33. Ticket Security

Store a hash or safe identifier rather than unnecessarily preserving raw ticket material.

Ticket values include only the minimum authorization metadata necessary for connection establishment.

---

# 34. Ticket Consumption

Conceptually:

```text
lookup
↓
validate
↓
delete
↓
return payload
```

must occur atomically.

This prevents simultaneous reuse.

---

# 35. Redis Cache Use

Selected metadata may eventually be cached.

Examples:

```text
permission lookup
document metadata
```

But cache additions require:

* clear invalidation strategy,
* measured benefit,
* no correctness dependency on stale cache.

Do not cache merely because Redis is available.

---

# 36. Permission Revocation

When permissions change:

1. PostgreSQL transaction commits,
2. relevant cache entry is invalidated,
3. connected instances are notified,
4. revoked connections are closed.

PostgreSQL authorization remains authoritative.

---

# 37. Redis Failure Modes

## Redis completely unavailable

New distributed collaboration may pause.

The system must not create uncontrolled multiple document leaders.

REST operations that do not depend on Redis may continue where safe.

---

## Pub/Sub temporarily interrupted

Document operations remain recoverable through PostgreSQL revision history.

---

## Presence data lost

Users may temporarily disappear/reappear.

Document content remains unaffected.

---

## Leader keys lost

Instances must re-elect document leaders.

Canonical state must be reconstructed from PostgreSQL before sequencing resumes.

---

# 38. Redis Restart Recovery

After Redis restart:

```text
ephemeral leases gone
presence gone
tickets gone
subscriptions reconnect
```

Spring instances:

1. reconnect,
2. reacquire leadership as needed,
3. reconstruct canonical document state,
4. rebuild presence from active sockets,
5. require expired connection tickets to be recreated.

No committed document history is lost.

---

# 39. Redis and Persistence Separation

The key architectural rule is:

```text
Redis unavailable
≠
document data lost
```

PostgreSQL must contain enough data to recover.

---

# 40. Metrics

Track:

```text
Redis connection health
command latency
publish latency
published messages/sec
received messages/sec
leader acquisitions
leader renewals
leader losses
gap recoveries
presence keys
ticket creation
ticket failures
cursor messages/sec
```

---

# 41. Load Testing

Multi-instance load tests must deliberately:

* route clients across separate Spring instances,
* edit the same document,
* kill a leader,
* allow another leader to take over,
* verify convergence.

A single-instance load test is insufficient evidence for horizontal scaling.

---

# 42. Chaos Tests

Required scenarios:

```text
kill document leader
kill non-leader server
restart Redis
temporarily disconnect one server from Redis
duplicate accepted event
drop one accepted event
expire leader lease
rapid leader transfer
```

---

# 43. Consequences

## Positive

* enables horizontal WebSocket scaling,
* separates connection ownership from document sequencing,
* fast ephemeral communication,
* natural presence storage,
* clear recovery path through PostgreSQL.

## Negative

* introduces distributed lease complexity,
* Pub/Sub is not durable,
* split-brain safety must be tested,
* Redis becomes necessary for full multi-instance collaboration,
* additional failure modes exist.

---

# 44. Frozen Decisions

ADR-004 freezes:

1. Redis for distributed coordination.
2. Redis Pub/Sub for live cross-instance propagation.
3. PostgreSQL for durable canonical history.
4. One document leader at a time.
5. Redis leases for leader ownership.
6. Conditional PostgreSQL revision updates as additional protection.
7. Clients may connect to any backend instance.
8. Sticky sessions are not required for correctness.
9. Revision-gap recovery after Pub/Sub loss.
10. Redis-backed ephemeral presence and realtime tickets.

---

# 45. Superseding This ADR

Moving cross-instance coordination to:

```text
Kafka
NATS
RabbitMQ
PostgreSQL LISTEN/NOTIFY
another distributed-lock system
```

requires a new ADR if it replaces Redis's architectural role.
