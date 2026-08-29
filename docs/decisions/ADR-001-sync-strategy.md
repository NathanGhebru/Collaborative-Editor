# Operational Transformation Synchronization Strategy

**Status:** Accepted
**Date:** 2026-08-28
**Decision owner:** Codex
**Reviewer:** Antigravity
**Scope:** Synchronization algorithm, operation semantics, ordering, convergence, and client/server authority. Wire payloads belong in `docs/REALTIME_PROTOCOL.md`.

The accepted decision selects the synchronization family, authority model, primitive text semantics, ordering, durability boundary, and epoch model. The open correctness details in Section 38 must be resolved before the affected protocol behavior is frozen.

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

The following composite operation is required as a possible canonical transformation result, but client-authored use is not yet implementation-ready:

```text
GROUP
```

`GROUP` represents one logical operation containing multiple primitives. Insert-wins transformation can produce a `GROUP` even when the client submitted one `DELETE`, so the server-side composite model cannot be skipped. Section 21 does not yet define a complete deterministic group-vs-group transformation algorithm; OT implementation is blocked until canonical composite behavior is resolved. Client-authored `GROUP` may still be deferred separately.

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

# 9. Proposed GROUP

A logical editor action may consist of multiple primitives.

Example replacement:

```json
{
  "kind": "GROUP",
  "operations": [
    {
      "kind": "DELETE",
      "position": 5,
      "length": 4
    },
    {
      "kind": "INSERT",
      "position": 5,
      "text": "new"
    }
  ]
}
```

If the outstanding transformation decision is resolved as intended, a group:

* is transformed as one user intent,
* is persisted atomically,
* receives one client operation ID,
* receives one canonical revision.

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

the insertion survives.

The transformed delete becomes a GROUP deleting only the original characters.

Example:

```text
A = DELETE(10, 10)
B = INSERT(15, "xyz")
```

A originally deletes:

```text
original characters 10..19
```

After B, transform A into conceptually:

```text
DELETE(10, 5)
DELETE(13, 5)
```

executed sequentially.

The concurrent insertion `"xyz"` survives.

---

# 19. DELETE vs DELETE

Let:

```text
A = DELETE(a, lengthA)
B = DELETE(b, lengthB)
```

Compute the overlap between the two original ranges.

## B entirely before A

If:

```text
b + lengthB <= a
```

then:

```text
A.position -= lengthB
```

---

## B entirely after A

If:

```text
b >= a + lengthA
```

A remains unchanged.

---

## Overlap

The portion already removed by B is removed from A's intended delete.

Compute:

```text
overlap =
max(
  0,
  min(a + lengthA, b + lengthB)
  -
  max(a, b)
)
```

Then:

```text
newLength = lengthA - overlap
```

If:

```text
newLength == 0
```

A becomes:

```text
NO_OP
```

Otherwise:

* if A begins before B, its transformed position remains `a`,
* if A begins inside B's deleted range, its transformed position becomes `b`.

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

# 21. Proposed Transformation of Groups

A GROUP is treated as an ordered sequence of primitives representing one user action.

When transforming a group against another operation:

1. transform each affected primitive while preserving group order,
2. transform subsequent group positions relative to earlier transformed primitives,
3. remove internal `NO_OP` primitives,
4. collapse the entire group to `NO_OP` only if no effective primitive remains.

A dedicated implementation module must own this behavior.

Do not distribute transformation logic across controllers, WebSocket handlers, and UI components.

The steps above express intent but are not a complete algorithm for all primitive/group and group/group combinations. Exact position rebasing, tie-breaking between composite operations, validation order, and canonical test vectors remain an implementation blocker; see Section 38.

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

The client maintains:

```text
confirmed document state
confirmedRevision
pending local operations
visible optimistic state
```

The visible editor may include changes the server has not yet acknowledged.

This state description does not by itself define how the server interprets a later local operation whose position depends on an earlier unacknowledged local operation. The protocol must select a causal submission model before allowing multiple operations in flight; see Section 38.

---

# 24. Client Rebase

Suppose:

```text
confirmedRevision = 20
pending operation = L
```

A remote canonical operation:

```text
R at revision 21
```

arrives.

Conceptually:

```text
R' = transform(R, L)
L' = transform(L, R)
```

The client:

1. applies R' to the visible document,
2. replaces pending L with L',
3. advances confirmed revision to 21.

With multiple local pending operations, transformation proceeds through the pending queue in order.

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

# 38. Open Correctness Details

The following decisions are unresolved. They are not permission for an implementation to choose ad hoc behavior.

## 38.1 Multiple Pending Local Operations

`baseRevision` identifies canonical server history but does not identify dependencies between local operations. If operation B is authored against visible text that already includes unacknowledged operation A, treating both as independent operations against the same `baseRevision` can transform B incorrectly.

Before client/server OT integration, the protocol must choose and specify one causal model, such as a single in-flight operation plus a composed buffer, or explicit per-client predecessor/sequence metadata with corresponding server rules. The choice requires canonical multi-operation test vectors shared by Java and TypeScript.

## 38.2 Composite `GROUP` Transformation

Sections 9 and 21 do not completely define deterministic transformation of `GROUP` against every primitive and another `GROUP`. Because insert-wins `DELETE`-against-`INSERT` can generate a composite canonical result, the implementation must complete internal/server-emitted composite semantics unless a new ADR changes that accepted intent policy. A separate choice may enable client-authored `GROUP` or defer client-authored replacements to a causally defined primitive sequence.

## 38.3 Unacknowledged Intent Across Epoch Change

An operation from an old epoch cannot be applied normally after version restore. The product nevertheless prohibits silent loss of local text. Before connected-client restore is implemented, the protocol and UX must decide how old-epoch unacknowledged intent is preserved for user recovery, and which portions, if any, can be safely translated into new-epoch operations.

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
