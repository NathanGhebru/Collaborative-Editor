# Operational Transformation Synchronization Strategy

**Status:** Accepted
**Date:** 2026-08-28
**Decision owner:** Codex
**Reviewer:** Antigravity
**Scope:** Synchronization algorithm, operation semantics, ordering, convergence, and client/server authority. Wire payloads belong in `docs/REALTIME_PROTOCOL.md`.

The accepted decision selects the synchronization family, authority model, primitive text semantics, ordering, durability boundary, and epoch model. Correctness details were frozen under OT-001 in Section 38 and `docs/ot-test-vectors.json`.

---

## 1. Context

The Real-Time Collaborative Editor must allow many users to modify the same document concurrently while ensuring that all connected clients eventually converge to the same state.

Target capabilities include:

* 50+ simultaneous editors per document
* 1,000+ document operations/sec
* <100 ms p95 synchronization latency
* disconnect and reconnect recovery
* horizontally scaled Spring Boot servers
* Redis Pub/Sub propagation
* version history
* persistent recovery through PostgreSQL

The synchronization mechanism must integrate naturally with the project's primary backend stack:

```text
Java
Spring Boot
PostgreSQL
Redis
WebSockets
```

The primary architecture question is whether concurrent editing should use:

```text
CRDT
or
Operational Transformation
```

## 1.1 Problem

The system needs one synchronization model that preserves concurrent user intent, produces deterministic convergence, supports idempotent reconnect, integrates with durable canonical history, and remains practical to implement equivalently in Java and TypeScript.

---

# 2. Decision

The project will use:

> **Server-authoritative Operational Transformation (OT) for collaborative document synchronization.**

Each document has:

```text
syncEpoch
currentRevision
```

Each client operation contains:

```text
documentId
syncEpoch
clientId
clientOperationId
baseRevision
operation
```

One distributed document leader/sequencer is responsible for:

1. receiving operations,
2. identifying the operation's base revision,
3. transforming stale operations against accepted canonical operations,
4. assigning the next canonical revision,
5. applying the operation,
6. persisting it,
7. propagating it to all connected users.

The server defines canonical operation ordering.

---

# 3. Why OT

OT was selected because it makes the synchronization algorithm an explicit part of the Java/Spring Boot backend rather than primarily delegating collaboration correctness to an external synchronization engine.

This project is intended to demonstrate:

* distributed synchronization
* concurrency reasoning
* WebSocket architecture
* server-side state management
* Redis coordination
* persistence
* failure recovery
* measurable performance

A server-authoritative OT design exposes those engineering problems directly.

---

# 4. Alternatives Considered

## 4.1 CRDT using an external JavaScript-oriented collaboration library

Example conceptual architecture:

```text
React
  ↓
CRDT client library
  ↓
opaque CRDT updates
  ↓
Spring WebSocket relay
```

### Advantages

* mature collaboration algorithms may already exist,
* easier offline editing,
* less custom transformation logic,
* peer-like update merging.

### Disadvantages

* less synchronization logic would live in the Java backend,
* Spring Boot could become primarily an update relay,
* persistence of opaque CRDT state complicates backend reasoning,
* less direct control over canonical operation ordering,
* weaker demonstration of Java-based concurrency logic.

### Decision

Rejected for the initial implementation.

A future CRDT migration would require a new ADR and a protocol version change.

---

## 4.2 Fully client-authoritative OT

Clients could transform operations independently and send already-reconciled results.

### Advantages

* less backend work,
* potentially lower server computation.

### Disadvantages

* difficult to establish one canonical history,
* inconsistent client implementations could corrupt convergence,
* poor trust boundary,
* more difficult reconnect behavior,
* harder persistence semantics.

### Decision

Rejected.

The server remains authoritative.

---

## 4.3 Last-write-wins document replacement

Each user could repeatedly send the complete latest document.

### Advantages

* trivial implementation.

### Disadvantages

* concurrent edits overwrite one another,
* bandwidth grows with document size,
* no meaningful conflict resolution,
* poor performance,
* unsuitable for real collaboration.

### Decision

Rejected.

---

# 5. Initial Document Representation

The first synchronization implementation operates on:

> **A linear Unicode text document.**

Operations manipulate positions using:

> **UTF-16 code-unit offsets.**

This is intentionally chosen because JavaScript strings and browser editor positions naturally use UTF-16 semantics.

The Java implementation must explicitly follow equivalent indexing behavior.

The backend must never interpret positions as UTF-8 byte offsets.

---

# 6. Primitive Operations

The accepted primitive operation set is:

```text
INSERT
DELETE
```

The following composite operation is accepted as a server-emitted canonical transformation result:

```text
GROUP
```

`GROUP` represents one logical operation containing multiple primitives executed sequentially. Insert-wins transformation produces a `GROUP` when a concurrent insertion falls strictly inside a deleted range, splitting the deletion into two segments around the inserted text. Client-authored wire-level `GROUP` is deferred for protocol v1; clients transmit individual primitive operations.

---

# 7. INSERT

Representation:

```json
{
  "kind": "INSERT",
  "position": 10,
  "text": "hello"
}
```

Meaning:

> Insert `text` before the character at the supplied UTF-16 position.

Valid range:

```text
0 <= position <= document length
```

---

# 8. DELETE

Representation:

```json
{
  "kind": "DELETE",
  "position": 10,
  "length": 5
}
```

Meaning:

> Delete the indicated number of UTF-16 code units beginning at `position`.

Valid range:

```text
position >= 0
length > 0
position + length <= document length
```

---

# 9. Server-Emitted GROUP

A logical operation emitted by the server may consist of multiple primitives.

Example split deletion resulting from insert-wins transformation:

```json
{
  "kind": "GROUP",
  "operations": [
    {
      "kind": "DELETE",
      "position": 5,
      "length": 2
    },
    {
      "kind": "DELETE",
      "position": 8,
      "length": 2
    }
  ]
}
```

Semantics:

* primitives within a `GROUP` are applied sequentially (each primitive operates on the state produced by prior primitives in the group),
* persisted and broadcast atomically,
* receives one client operation ID,
* consumes exactly one canonical revision,
* if transformation reduces a `GROUP` to 1 primitive, it flattens to that primitive; if 0 primitives remain, it collapses to `NO_OP`.

---

# 10. NO_OP

Transformation may eliminate an operation.

Example:

```text
Client A deletes characters 5–10.
Client B concurrently deletes exactly characters 5–10.
```

After one delete is accepted, transforming the other may produce:

```text
NO_OP
```

The second operation still receives:

* a canonical revision,
* an acknowledgement,
* idempotency history.

This ensures deterministic operation accounting.

---

# 11. Revision Model

For one synchronization epoch:

```text
revision 0
revision 1
revision 2
revision 3
...
```

Revision `0` represents the initial state of that epoch.

Every accepted client operation after that consumes exactly one revision.

Example:

```text
currentRevision = 104

accept operation A
→ revision 105

accept operation B
→ revision 106
```

Revisions are:

* strictly increasing,
* canonical,
* server-assigned,
* gap-free in durable history.

---

# 12. Synchronization Epoch

Every document has:

```text
syncEpoch : UUID
```

Normal edits remain within the same epoch.

A new epoch is created when the canonical document timeline is intentionally replaced.

Initial trigger:

```text
version restore
```

Example:

```text
old epoch:
E1
revision 9,412

restore version 17

new epoch:
E2
revision 0
```

Old outstanding operations from E1 cannot be applied to E2.

---

# 13. Operation Identity

Each browser/client has:

```text
clientId
```

Each generated operation has:

```text
clientOperationId
```

The idempotency key is:

```text
(documentId,
 syncEpoch,
 clientId,
 clientOperationId)
```

Retries must reuse the same key.

---

# 14. Canonical Tie-Break Ordering

Concurrent operations occasionally require deterministic tie breaking.

Define:

```text
operationKey =
(clientId, clientOperationId)
```

UUIDs are compared using their canonical lowercase string representation lexicographically.

The operation with the smaller key has precedence.

This rule must be implemented identically in Java and TypeScript.

---

# 15. Transformation Definition

Define:

```text
transform(A, B)
```

as:

> Transform operation A so it preserves A's intended effect after concurrent operation B has already been applied.

Both operations originally refer to the same logical base state.

---

# 16. INSERT vs INSERT

Let:

```text
A = INSERT(a, textA)
B = INSERT(b, textB)
```

If:

```text
a < b
```

A remains unchanged.

If:

```text
a > b
```

A becomes:

```text
INSERT(a + length(textB), textA)
```

If:

```text
a == b
```

use the operation-key ordering.

If A has precedence over B:

```text
A remains at a
```

If B has precedence:

```text
A.position += length(textB)
```

---

# 17. INSERT vs DELETE

Let:

```text
A = INSERT(a, text)
B = DELETE(b, lengthB)
```

Let:

```text
endB = b + lengthB
```

If:

```text
a <= b
```

A remains unchanged.

If:

```text
a >= endB
```

A becomes:

```text
INSERT(a - lengthB, text)
```

If:

```text
b < a < endB
```

A collapses to the deletion boundary:

```text
INSERT(b, text)
```

### Intent rule

A concurrent insertion survives a deletion.

The deletion does not automatically destroy text another collaborator concurrently inserted.

---

# 18. DELETE vs INSERT

Let:

```text
A = DELETE(a, lengthA)
B = INSERT(b, textB)
```

Let:

```text
endA = a + lengthA
insertLength = length(textB)
```

## B before A

If:

```text
b < a
```

A becomes:

```text
DELETE(a + insertLength, lengthA)
```

---

## B after A

If:

```text
b >= endA
```

A remains unchanged.

---

## B exactly at A's start

If:

```text
b == a
```

the inserted text survives.

A becomes:

```text
DELETE(a + insertLength, lengthA)
```

---

## B inside A's deleted range

If:

```text
a < b < endA
```

the insertion survives under insert-wins semantics.

The transformed delete becomes a sequential `GROUP` deleting only the original characters around the insertion:

```text
A' = GROUP([
  DELETE(a, b - a),
  DELETE(a + insertLength, endA - b)
])
```

Primitives execute sequentially: the first primitive deletes the `b - a` code units preceding `b` at position `a`. The second primitive deletes the remaining `endA - b` code units of original text starting at `a + insertLength` (skipping the inserted text).

---

# 19. DELETE vs DELETE

Let:

```text
A = DELETE(a, lengthA)
B = DELETE(b, lengthB)
```

Compute the overlap between the two original ranges:

## B entirely before A

If:

```text
b + lengthB <= a
```

then:

```text
A' = DELETE(a - lengthB, lengthA)
```

---

## B entirely after A

If:

```text
b >= a + lengthA
```

then:

```text
A' = DELETE(a, lengthA)
```

---

## Overlapping deletes

Compute:

```text
overlap = max(0, min(a + lengthA, b + lengthB) - max(a, b))
newLength = lengthA - overlap
```

If:

```text
newLength == 0
```

then `B` fully covers `A`, so:

```text
A' = NO_OP
```

Otherwise (`newLength > 0`):

1. If `A` begins before `B` (`a < b`):
   - If `a + lengthA <= b + lengthB`: `A` overlaps `B` at its tail, so:
     `A' = DELETE(a, newLength)`
   - If `a + lengthA > b + lengthB` (`B` is strictly inside `A`): `B` cuts out a middle segment, collapsing the remaining characters into a contiguous range, so:
     `A' = DELETE(a, lengthA - lengthB)`

2. If `A` begins at or after `B` (`a >= b`):
   - Since `newLength > 0` and `B` does not fully cover `A`, `A` extends past `b + lengthB`. The remaining characters shift to start at `b`:
     `A' = DELETE(b, newLength)`

---

# 20. Insert-Wins Boundary Policy

The project explicitly adopts:

> **Concurrent insertions survive deletions.**

Example:

```text
Original:
abcdefghij

A:
delete cdef

B:
insert XYZ between d and e
```

The converged result must preserve:

```text
XYZ
```

rather than deleting it merely because it was inserted inside a concurrently deleted range.

This decision is why transforming a DELETE against an INSERT may create a GROUP.

---

# 21. Transformation of Groups and Composites

A `GROUP` is treated as an ordered sequence of primitives $[P_1, P_2, \dots, P_k]$ representing one logical action executed sequentially.

### 21.1 Identity and NO_OP Rules
- `transform(NO_OP, X) = NO_OP`
- `transform(X, NO_OP) = X`

### 21.2 Primitive vs GROUP
When a primitive operation $P$ is transformed against a group $G = [P_1, P_2, \dots, P_k]$:
$P$ is transformed sequentially against each primitive in $G$:
```text
P_0 = P
P_i = transform(P_{i-1}, P_i)  for i = 1..k
transform(P, G) = P_k
```

### 21.3 GROUP vs Primitive
When a group $G = [P_1, P_2, \dots, P_k]$ is transformed against a primitive operation $P$:
Each primitive in $G$ is transformed against the progressively evolving $P$:
```text
P_0 = P
For i = 1..k:
  P_i' = transform(P_i, P_{i-1})
  P_i = transform(P_{i-1}, P_i)
```
Collect all non-`NO_OP` transformed primitives $P_i'$.

### 21.4 GROUP vs GROUP
When group $G_A = [P_{A,1}, \dots, P_{A,m}]$ transforms against group $G_B$:
```text
G_B_0 = G_B
For j = 1..m:
  P_{A,j}' = transform(P_{A,j}, G_B_{j-1})
  G_B_j = transform(G_B_{j-1}, P_{A,j})
```
Collect all non-`NO_OP` transformed primitives $P_{A,j}'$.

### 21.5 Flattening Rules
The resulting list of effective primitives is simplified:
- 0 active primitives remaining: returns `NO_OP`.
- 1 active primitive remaining: returns that single primitive $P'$.
- 2 or more active primitives: returns `GROUP([P_1', P_2', ...])`.

---

# 22. Server Processing Algorithm

For operation O:

```text
receive O
    ↓
validate permission
    ↓
validate epoch
    ↓
check operation ID
    ↓
already accepted?
 ┌──────┴──────┐
yes            no
 │              │
return        validate
existing      operation
result           ↓
              load canonical
              operations after
              O.baseRevision
                   ↓
              transform O
              sequentially
                   ↓
              validate transformed O
                   ↓
              apply to canonical state
                   ↓
              assign next revision
                   ↓
              persist
                   ↓
              publish
                   ↓
              acknowledge
```

---

# 23. Client Processing Model

The client maintains a 3-state collaboration state machine:

1. **State 1: Synchronized** (`inFlightOperation == null`, `pendingBuffer.isEmpty()`)
   - `confirmedState == optimisticState`
   - `confirmedRevision == currentKnownRevision`

2. **State 2: Awaiting In-Flight** (`inFlightOperation != null`, `pendingBuffer.isEmpty()`)
   - Exactly one operation $A$ was transmitted with `baseRevision = confirmedRevision`.
   - Local edits have not yet occurred since transmitting $A$.

3. **State 3: Awaiting with Buffer** (`inFlightOperation != null`, `pendingBuffer = [B_1, ..., B_n]`)
   - Operation $A$ remains in-flight over WebSocket.
   - Additional local edits $B_1, \dots, B_n$ occurred while awaiting acknowledgement for $A$.
   - Buffered edits are kept in a local sequential queue and are **NOT** sent over the wire until $A$ is acknowledged.
   - Each buffered edit $B_i$ is expressed relative to the optimistic state containing $A$ and prior buffered edits $B_1 \dots B_{i-1}$.

This single-in-flight design eliminates wire-level causality ambiguity, preserves clean base revisions, and prevents race conditions.

---

# 24. Client Rebase and Acknowledgement

### 24.1 Remote Canonical Operation $R$ Arrives
When a remote canonical operation $R$ arrives at `confirmedRevision + 1`:

1. If in State 1 (Synchronized):
   - Apply $R$ to `confirmedState` and `optimisticState`.
   - Advance `confirmedRevision = R.revision`.

2. If in State 2 (Awaiting In-Flight $A$):
   - $R' = transform(R, A)$
   - $A' = transform(A, R)$
   - Apply $R$ to `confirmedState`.
   - Apply $R'$ to `optimisticState`.
   - Replace in-flight $A$ with $A'$.
   - Advance `confirmedRevision = R.revision`.

3. If in State 3 (Awaiting with Buffer $A$ and $[B_1, \dots, B_n]$):
   - $R_0 = transform(R, A)$
   - $A' = transform(A, R)$
   - For $i = 1 \dots n$:
     - $R_i = transform(R_{i-1}, B_i)$
     - $B_i' = transform(B_i, R_{i-1})$
   - Apply $R$ to `confirmedState`.
   - Apply $R_n$ to `optimisticState`.
   - Replace in-flight $A$ with $A'$.
   - Replace `pendingBuffer` with $[B_1', \dots, B_n']$.
   - Advance `confirmedRevision = R.revision`.

### 24.2 Acknowledgement for In-Flight $A$ Arrives
When `server.operation_ack` for $A$ is received:
1. $A$ is confirmed; clear `inFlightOperation = null`.
2. If `pendingBuffer` is non-empty:
   - Dequeue the first buffered edit $B_1$ (or compose the entire pending buffer into one composite operation).
   - Set $B_1$'s `baseRevision = confirmedRevision`.
   - Set $B_1$ as the new `inFlightOperation`.
   - Transmit $B_1$ via `client.operation`.
   - Transition to State 2 (or State 3 if further buffered edits remain).
3. If `pendingBuffer` is empty:
   - Transition to State 1 (Synchronized).

---

# 25. Pending Operation Limit

Clients may not accumulate an unbounded number of unacknowledged edits.

A configurable maximum must exist.

Initial recommended value:

```text
100 pending logical operations
```

When reached:

* local edits may temporarily stop transmitting,
* UI may enter backpressure state,
* connection health should be investigated.

This value must be tuned through testing.

---

# 26. Stale Clients

If a client submits:

```text
baseRevision < currentRevision
```

the operation may be transformed if all required canonical operations can be retrieved.

If transformation history is unavailable or exceeds configured recovery limits:

```text
full resynchronization
```

is required.

---

# 27. Future Clients

If:

```text
baseRevision > currentRevision
```

the client is inconsistent.

The operation is rejected.

The client performs a full resynchronization.

---

# 28. Disconnect Behavior

Unacknowledged client operations remain locally queued.

After reconnect:

1. synchronize with canonical history,
2. identify operations already accepted,
3. rebase remaining local operations,
4. retransmit them using their original IDs.

User edits should not disappear simply because of a temporary network interruption.

---

# 29. Persistence Interaction

An operation is not considered durably accepted until its PostgreSQL transaction commits.

Canonical ordering:

```text
transform
   ↓
apply
   ↓
batch
   ↓
PostgreSQL commit
   ↓
Redis propagation
   ↓
client acknowledgement
```

---

# 30. Version History Interaction

User-visible document versions are not equivalent to OT revisions.

A version references a materialized state at:

```text
syncEpoch
revision
```

Restoring a version creates:

```text
new syncEpoch
revision 0
```

rather than rewinding the revision counter inside the existing epoch.

---

# 31. Rich Text

Protocol v1 synchronizes a linear text model.

Rich structured editing is deferred.

Reasons:

* text OT can be rigorously tested first,
* ProseMirror tree operations greatly expand transformation complexity,
* synchronization correctness is more important than feature breadth.

Rich-text collaborative semantics require a later ADR.

Basic frontend formatting should not be presented as fully collaborative unless its representation is included in the synchronized model.

---

# 32. Testing Requirements

The OT module requires exhaustive deterministic unit tests.

Required categories:

```text
INSERT / INSERT
INSERT / DELETE
DELETE / INSERT
DELETE / DELETE
same-position operations
boundary positions
overlapping deletes
full-overlap deletes
nested deletes
insert inside deleted range
operation groups
NO_OP
duplicate operation
stale revision
```

---

# 33. Property Testing

In addition to fixed examples, generate randomized:

```text
initial document
operation A
operation B
```

and verify convergence.

For valid concurrent A and B:

```text
apply(A)
then apply(transform(B, A))
```

must produce the same document as:

```text
apply(B)
then apply(transform(A, B))
```

for supported transformation semantics.

Thousands of randomized cases should run in CI.

---

# 34. Multi-Client Testing

Beyond pairwise operations, tests must simulate:

```text
3 clients
10 clients
50 clients
```

with randomized concurrent operations and ensure final convergence.

---

# 35. Performance Requirements

OT transformation cost must be measured.

Track:

```text
operations transformed/sec
transformation latency
history length
document length
CPU
allocations
```

Performance optimizations must not weaken convergence tests.

---

# 36. Consequences

## Positive

* synchronization logic lives in Java,
* canonical operation history is explicit,
* persistence is easy to reason about,
* revisions simplify reconnect/gap detection,
* server authorization remains authoritative,
* architecture demonstrates substantial distributed-systems work.

## Negative

* OT is difficult to implement correctly,
* both Java and TypeScript require equivalent transformation semantics,
* rich text is harder than plain text,
* server sequencing creates additional distributed coordination,
* custom correctness testing is mandatory.

---

# 37. Risks

Primary risks:

```text
incorrect transformation rules
client/server semantic drift
split-brain sequencers
long transformation history
high-frequency persistence
reconnect edge cases
```

Each risk has dedicated automated testing elsewhere in the architecture.

---

# 38. Resolved Synchronization Details (OT-001)

The following synchronization details were frozen under **OT-001**:

## 38.1 Multiple Pending Local Operations (Resolved)

The protocol adopts the single in-flight operation model with a sequential local pending buffer (Section 23 and Section 24). At most one operation is in-flight over WebSocket at any given time. Additional local user edits are appended to a local buffer and rebased progressively against arriving remote canonical operations. When acknowledgement arrives, the next buffered operation (or composed buffer) is dispatched with `baseRevision = confirmedRevision`. Canonical multi-operation rebase test vectors are frozen in `docs/ot-test-vectors.json`.

## 38.2 Composite `GROUP` Transformation (Resolved)

Complete deterministic transformation semantics for server-emitted `GROUP` composites against primitives and other `GROUP` composites are frozen in Section 21. Client-authored `GROUP` operations are deferred for protocol v1; clients decompose replacements and submit individual primitive operations. The server emits `GROUP` operations only when insert-wins splits a `DELETE` around a concurrent `INSERT`.

## 38.3 Unacknowledged Intent Across Epoch Change (HIST-001)

An operation from an old epoch cannot be applied normally after version restore (`EPOCH_MISMATCH`). As specified in `REALTIME_PROTOCOL.md` and deferred to roadmap task `HIST-001`, the client retains local unacknowledged text in memory, alerts the user to the timeline reset, and provides recovery UX to prevent data loss. Safe automatic rebase is prohibited across epoch boundaries.

# 39. Decision Invariants

The following are frozen by this ADR:

1. Synchronization uses OT.
2. The Spring backend is authoritative.
3. Each document has one canonical operation order.
4. Each operation has a stable client identity.
5. Each accepted logical operation receives one canonical revision.
6. Insertions survive concurrent deletions.
7. Version restore creates a new synchronization epoch.
8. UTF-16 code units are the protocol's position unit.
9. Retries must be idempotent.
10. Client/server transformation semantics must match.

---

# 40. Superseding This ADR

Replacing OT with:

* CRDT,
* last-write-wins,
* full-document replacement,
* another synchronization algorithm,

requires a new ADR explicitly superseding ADR-001.

Such a change also requires review of:

```text
ARCHITECTURE.md
REALTIME_PROTOCOL.md
DATABASE.md
API.md
TESTING.md
BENCHMARKS.md
```

---
