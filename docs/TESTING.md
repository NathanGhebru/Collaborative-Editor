# Testing Strategy — Real-Time Collaborative Editor

## Status

**Status:** Accepted
**Owner:** Codex
**Frontend/E2E owner:** Antigravity

This document defines the authoritative testing strategy for the Real-Time Collaborative Editor.

A feature is not complete because:

* it compiles,
* it works once manually,
* an agent reports that it works.

A feature is complete only when its required automated verification passes.

---

# 1. Testing Goals

The test suite must provide confidence in:

```text
correctness
+
convergence
+
durability
+
authorization
+
failure recovery
+
browser behavior
+
horizontal scaling
+
performance regression detection
```

The testing system must make:

```text
working
```

and:

```text
broken
```

distinguishable through executable commands.

---

# 2. Testing Layers

The project uses the following verification hierarchy:

```text
OT property/unit tests
        ↓
backend unit tests
        ↓
backend service tests
        ↓
PostgreSQL integration tests
        ↓
Redis integration tests
        ↓
WebSocket integration tests
        ↓
multi-instance integration tests
        ↓
frontend unit/component tests
        ↓
Playwright browser tests
        ↓
multi-browser collaboration tests
        ↓
failure/chaos tests
        ↓
load tests
        ↓
AWS smoke tests
```

No single layer replaces the others.

---

# 3. Authoritative Commands

The project must eventually expose:

```bash
./scripts/test-all.sh
./scripts/test-backend.sh
./scripts/test-frontend.sh
./scripts/test-integration.sh
./scripts/test-websocket.sh
./scripts/test-persistence.sh
./scripts/test-redis.sh
./scripts/test-ot.sh
./scripts/test-e2e.sh
./scripts/test-multi-instance.sh
./scripts/test-security.sh
./scripts/validate-docs.sh
```

Performance commands are defined separately:

```bash
./scripts/benchmark-small.sh
./scripts/benchmark-full.sh
```

---

# 4. Full Verification

The canonical local verification command is:

```bash
./scripts/test-all.sh
```

Success means:

```text
exit code 0
```

It should run all tests appropriate for normal CI.

The most expensive full load tests do not necessarily run on every commit.

---

# 5. Test Isolation

Tests must not depend on:

* developer-specific databases,
* production resources,
* previously existing local data,
* test execution order,
* another test leaving state behind.

Integration tests should create isolated infrastructure using:

```text
Testcontainers
```

or equivalent Docker-managed resources.

---

# 6. Backend Unit Tests

Backend unit tests should cover isolated Java logic without requiring network infrastructure.

Examples:

```text
validation
authorization helpers
operation parsing
revision validation
batch construction
snapshot logic
version logic
rate limiting helpers
error mapping
```

Critical distributed behavior must not rely exclusively on mocks.

---

# 7. OT Module Testing

The OT implementation is one of the most critical parts of the project.

It must have a dedicated test module.

Required pairwise transformation cases:

```text
INSERT vs INSERT
INSERT vs DELETE
DELETE vs INSERT
DELETE vs DELETE
```

---

# 8. INSERT/INSERT Tests

Test at minimum:

```text
insert before insert
insert after insert
same-position insert
different text lengths
empty document
document beginning
document end
Unicode/UTF-16 offsets
deterministic tie breaking
```

Same-position operations must produce the same ordering regardless of which client receives the other operation first.

---

# 9. INSERT/DELETE Tests

Test:

```text
insert before deleted range
insert at deletion start
insert inside deleted range
insert at deletion end
insert after deleted range
```

The project's insert-wins semantics must be explicitly verified.

---

# 10. DELETE/INSERT Tests

Test:

```text
insert before deletion
insert at deletion start
insert inside deletion
insert at deletion end
insert after deletion
```

An insertion inside a concurrent deletion must survive according to ADR-001.

---

# 11. DELETE/DELETE Tests

Test:

```text
non-overlapping A before B
non-overlapping B before A
partial overlap
full overlap
same range
A inside B
B inside A
touching boundaries
NO_OP result
```

---

# 12. Operation Group Tests

Server-generated composite operations are required by ADR-001's insert-wins policy and cannot be implemented until the composite-transformation blocker is resolved. Once resolved, test:

```text
replacement operations
group vs insert
group vs delete
group vs group
partial NO_OP inside group
whole group becoming NO_OP
```

One server-generated logical `GROUP` must consume one canonical revision. If client-authored `GROUP` remains disabled, protocol tests additionally verify that clients do not send it and servers reject it with `INVALID_OPERATION`.

---

# 13. OT Convergence Property

For valid concurrent operations `A` and `B` based on state `S`:

```text
apply(
    transform(B, A),
    apply(A, S)
)
```

must equal:

```text
apply(
    transform(A, B),
    apply(B, S)
)
```

for supported operations.

This must be tested through randomized/property-based tests.

---

# 14. Random OT Testing

Generate thousands of cases containing:

```text
random initial document
random operation A
random operation B
```

Verify:

* both operations are initially valid,
* both application orders converge,
* transformed operations remain valid.

The CI suite should run a deterministic seeded subset.

Longer randomized runs may run separately.

---

# 15. Multi-Operation OT Simulation

Pairwise convergence alone is insufficient.

Simulate:

```text
3 clients
10 clients
50 clients
```

performing randomized edits.

After all canonical operations are delivered:

```text
all client documents == server document
```

must hold.

---

# 16. Backend Service Tests

Test application services such as:

```text
document creation
document loading
document rename
document deletion
sharing
permission checking
version creation
version restore
realtime ticket creation
```

## REST API Contract Tests

For every endpoint in `docs/API.md`, automated tests must verify:

- method and path;
- authentication and role matrix;
- request validation and unknown-field policy;
- success status and response schema;
- every documented error code and HTTP mapping;
- pagination behavior where applicable;
- absence of undocumented body-edit REST writes;
- request ID propagation in headers and error bodies.

The frontend should also validate representative responses against shared runtime schemas or fixtures so backend/frontend contract drift fails CI.

---

# 17. Authentication Tests

Test:

```text
registration
duplicate username
duplicate email
password hashing
valid login
invalid login
token expiration
refresh
refresh rotation
logout
revoked refresh token
disabled account
```

Raw passwords/tokens must never appear in assertions generated from logs.

---

# 18. Authorization Tests

Every protected document operation must test:

```text
owner allowed
editor allowed where appropriate
editor denied where owner-only
unshared user denied
unauthenticated user denied
```

Required endpoints include:

```text
GET document
PATCH document
DELETE document
permissions
versions
restore
realtime ticket
```

---

# 19. WebSocket Authorization Tests

Verify:

```text
valid ticket connects
invalid ticket rejected
expired ticket rejected
ticket reuse rejected
ticket for wrong document rejected
revoked permission rejected
unauthorized document connection rejected
```

---

# 20. PostgreSQL Integration Tests

Use real PostgreSQL.

Do not use H2 or another substitute as the authoritative persistence test.

Test:

```text
schema migration
constraints
foreign keys
unique constraints
indexes where behavior matters
transaction rollback
conditional revision advancement
cascade deletion
```

---

# 21. Operation Persistence Tests

Test:

```text
single operation batch
multiple-operation batch
revision range
operation ID insertion
duplicate operation ID
transaction rollback
documents.current_revision advancement
wrong previous revision rejection
```

---

# 22. Persistence Atomicity

Simulate failure between logical persistence steps.

The system must never persist:

```text
currentRevision = 120
```

while canonical history only exists through:

```text
119
```

Likewise, operation IDs must not indicate committed operations whose batch was rolled back.

---

# 23. Snapshot Tests

Test:

```text
new document revision-0 snapshot with empty initial text
new document revision-0 snapshot with supplied initial text
snapshot at revision 0
snapshot after operations
correct content
correct epoch
correct revision
checksum verification
periodic snapshot trigger
```

---

# 24. Recovery Tests

Given:

```text
snapshot revision 500
operation history 501–725
```

a fresh backend must reconstruct exactly:

```text
revision 725
```

with content identical to the original leader.

---

# 25. Long Recovery Test

Generate at least:

```text
10,000 logical revisions
```

Then:

1. stop backend,
2. clear all in-memory state,
3. restart backend,
4. reconstruct document,
5. verify final content,
6. verify final revision.

This validates the version/revision target functionally.

---

# 26. Version History Tests

Test:

```text
automatic version
manual version
version numbering
historical content retrieval
version permissions
version labels
```

---

# 27. Version Restore Tests

Test:

```text
restore older version
new syncEpoch generated
revision resets to 0
old history remains
pre-restore state retained
connected clients receive reset
old-epoch operation rejected
```

---

# 28. Redis Integration Tests

Use a real Redis instance.

Test:

```text
leader lease acquisition
failed competing acquisition
lease renewal
lease expiration
lease release
old owner cannot delete new lease
realtime ticket storage
ticket TTL
single-use ticket
presence TTL
Pub/Sub
```

---

# 29. Redis Leader Tests

Two backend instances attempt leadership simultaneously.

Expected:

```text
exactly one succeeds
```

Then:

1. active leader stops renewing,
2. lease expires,
3. second instance acquires leadership,
4. reconstructs state,
5. continues revisions correctly.

---

# 30. Split-Brain Protection Test

Force a stale leader to believe it still owns the room.

Allow another leader to advance PostgreSQL.

The stale leader's conditional database update must fail.

Expected:

```text
no duplicate revision
no divergent canonical history
```

---

# 31. Redis Pub/Sub Tests

Test:

```text
operation publish
operation receive
duplicate event
missing revision
out-of-order observed revision
subscriber restart
```

---

# 32. Gap Recovery Test

Instance has:

```text
revision 100
```

Then receives:

```text
revision 103
```

Expected:

1. detect gap,
2. query PostgreSQL,
3. retrieve 101 and 102,
4. apply 101,
5. apply 102,
6. apply 103,
7. finish at 103.

---

# 33. Redis Restart Test

While collaboration is active:

1. restart Redis,
2. allow leases/presence to disappear,
3. reconnect backend instances,
4. reacquire leadership,
5. rebuild presence,
6. continue collaboration.

Expected:

```text
committed document content unchanged
```

---

# 34. WebSocket Integration Tests

Run the actual Spring WebSocket server.

Test:

```text
connect
client.hello
server.ready
operation send
canonical operation delivered to origin before operation ack
remote operation
cursor
presence
disconnect
reconnect
```

---

# 35. Stale Revision Test

Server current revision:

```text
100
```

Client submits against:

```text
95
```

Expected:

```text
transform through revisions 96–100
assign revision 101
```

if history remains available.

---

# 36. Future Revision Test

Server:

```text
100
```

Client submits:

```text
baseRevision = 102
```

Expected:

```text
reject
+
request resynchronization
```

---

# 37. Epoch Mismatch Test

Client:

```text
epoch A
```

Server:

```text
epoch B
```

Expected:

```text
operation rejected
server.resync_required
```

No transformation should occur.

---

# 38. Duplicate Client Operation Test

Submit operation ID `O1`.

Wait for commit.

Submit exactly `O1` again.

Expected:

```text
document changes once
same canonical revision returned
```

---

# 39. Failure Before Commit Test

Inject failure before PostgreSQL commit.

Expected:

```text
no success acknowledgement
no canonical revision advancement
client can retry
```

---

# 40. Failure After Commit Before Ack Test

Inject:

```text
PostgreSQL commit succeeds
server crashes before ack
```

Client reconnects and resends operation.

Expected:

```text
idempotency lookup succeeds
operation not duplicated
original revision returned
```

---

# 41. Multi-Instance Integration Tests

At least two independent Spring Boot instances must run against:

```text
same PostgreSQL
same Redis
```

Clients must be intentionally connected to different backend instances.

---

# 42. Multi-Instance Collaboration Test

```text
Browser A → Backend A
Browser B → Backend B
```

Both open the same document.

Verify:

```text
A edit reaches B
B edit reaches A
presence works
cursors work
document converges
```

---

# 43. Leader Failure Test

With active collaboration:

1. identify document leader,
2. terminate it,
3. keep other backend alive,
4. wait for lease expiration,
5. allow takeover,
6. reconnect affected browser,
7. continue editing.

Expected:

```text
no committed edits lost
no duplicate edits
revision sequence remains valid
```

---

# 44. Frontend Unit Tests

Frontend tests should cover:

```text
authentication state
document state
connection-state reducer
pending operation queue
OT transformation helpers
cursor state
presence aggregation
autosave status
error states
```

---

# 45. Client OT Equivalence Tests

The TypeScript OT implementation must run the same canonical test vectors as Java.

Store shared vectors in a language-neutral format such as:

```text
JSON
```

Both implementations consume them.

If Java and TypeScript disagree:

```text
CI fails
```

---

# 46. Playwright E2E Tests

Playwright is the authoritative browser-level test system.

Tests should use isolated browser contexts representing distinct users.

---

# 47. Authentication E2E

Test:

```text
register
login
logout
protected route
invalid credentials
session refresh where practical
```

---

# 48. Document CRUD E2E

Test:

```text
create
list
open
rename
delete
```

Verify persisted behavior after page reload.

---

# 49. Sharing E2E

User A:

```text
creates document
shares with B
```

User B:

```text
opens document
edits successfully
```

User C:

```text
has no access
```

Verify C cannot access through direct URL.

---

# 50. Basic Collaboration E2E

Two browser contexts:

```text
A
B
```

A types:

```text
hello
```

B must eventually display:

```text
hello
```

without page refresh.

---

# 51. Concurrent Collaboration E2E

A and B type concurrently into different and nearby positions.

Verify:

```text
A final text
==
B final text
==
server canonical text
```

---

# 52. Three-Client E2E

Use:

```text
A
B
C
```

Each edits simultaneously.

Verify:

* convergence,
* three-user presence,
* cursor visibility,
* no duplicate presence users.

---

# 53. Cursor E2E

Verify:

```text
remote cursor appears
cursor moves
selection appears
disconnect removes cursor
reconnect restores cursor functionality
```

Exact pixel-perfect positioning should be used only where stable.

Behavioral correctness is more important than brittle screenshot matching.

---

# 54. Presence E2E

Test:

```text
join
second tab
disconnect
close tab
reconnect
browser crash where simulatable
```

Presence eventually converges to correct users.

---

# 55. Autosave E2E

User types.

UI transitions:

```text
Saving...
↓
Saved
```

Reload page.

Expected:

```text
content remains
```

---

# 56. Persistence Failure UX Test

Where injectable:

1. make persistence fail,
2. edit document.

Frontend must not incorrectly display:

```text
Saved
```

It should show an error/degraded state.

---

# 57. Reconnect E2E

1. A and B connected,
2. disconnect A's WebSocket/network,
3. B continues editing,
4. A creates/retains local changes according to supported behavior,
5. reconnect A,
6. synchronize.

Expected:

```text
final convergence
```

---

# 58. Version Restore E2E

1. edit document,
2. create historical version,
3. edit more,
4. restore earlier version.

All connected browsers must:

```text
receive document reset
resynchronize
display restored content
```

---

# 59. Security Testing

Automated security verification should include:

```text
authorization bypass attempts
IDOR/document ID manipulation
expired access token
tampered access token
reused realtime ticket
wrong-document realtime ticket
oversized WebSocket message
malformed JSON
invalid operation positions
rate-limit behavior
```

---

# 60. Fuzz Testing

Protocol parsers should be fuzzed with malformed:

```text
JSON
message types
operation objects
UUIDs
positions
lengths
revision values
```

Malformed input must not:

```text
crash server
corrupt document
bypass authorization
```

---

# 61. Chaos/Failure Tests

Longer-running suites should test:

```text
backend kill
document leader kill
Redis restart
database temporary failure
network delay
message duplication
message omission
rapid reconnects
```

---

# 62. Load Tests

Load testing is separate from normal functional verification.

Load tests must still verify correctness indicators.

A throughput result with corrupted documents is a failed test.

---

# 63. Load Test Scenarios

Files under:

```text
load-tests/
```

should include:

```text
document-api.js
websocket-connect.js
concurrent-edit.js
reconnect.js
multi-document.js
presence.js
```

Additional scenarios may be added.

---

# 64. Small Performance Regression Test

CI may run a small bounded performance test.

Purpose:

```text
detect severe regression
```

not:

```text
prove final resume metrics
```

Example:

```text
20 clients
short duration
fixed operation rate
```

Fail only on large regressions to avoid flaky CI.

---

# 65. Full Benchmark Tests

Full benchmark execution is governed by:

```text
docs/BENCHMARKS.md
```

These establish measurable project claims.

---

# 66. GitHub Actions

CI should evolve toward:

```text
backend compile
      ↓
OT unit/property tests
      ↓
backend unit tests
      ↓
PostgreSQL integration
      ↓
Redis integration
      ↓
WebSocket integration
      ↓
frontend typecheck
      ↓
frontend unit tests
      ↓
Playwright
      ↓
multi-instance smoke test
      ↓
Docker build
      ↓
dependency/security checks
      ↓
small performance regression
```

---

# 67. CI Parallelism

Independent jobs may run in parallel, such as:

```text
backend unit
frontend unit
lint/typecheck
```

but final merge status must depend on all required checks.

---

# 68. Flaky Test Policy

A flaky test is a defect.

Do not:

```text
rerun until green
```

and ignore instability.

When a test is flaky:

1. identify reason,
2. fix synchronization/time assumptions,
3. use deterministic waiting conditions,
4. only then trust the test.

---

# 69. Time-Based Tests

Avoid:

```text
sleep(5000)
```

when waiting for distributed behavior.

Prefer:

```text
poll until condition
with explicit timeout
```

Example:

```text
wait until revision == expected
```

This makes tests faster and less flaky.

---

# 70. Test Data

Use generated accounts/documents.

Tests must not depend on personal user data.

Identifiers should normally be random UUIDs.

---

# 71. Required Task Tests

Every implementation task should specify exactly which commands prove completion.

Example:

```text
Verification:
./scripts/test-ot.sh
./scripts/test-websocket.sh
npx playwright test collaboration-basic
```

---

# 72. Regression Requirement

Every fixed correctness bug should receive a regression test whenever practical.

Process:

```text
reproduce bug
↓
write failing test
↓
fix bug
↓
test passes
```

---

# 73. Code Coverage

Coverage is useful but not a primary correctness metric.

Coverage targets may be used as a warning.

Do not write meaningless tests solely to increase percentages.

Critical modules such as:

```text
OT
authorization
persistence
leader coordination
```

should have especially strong behavioral coverage.

---

# 74. Test Ownership

## Codex

Primary ownership:

```text
Java unit tests
OT tests
PostgreSQL integration
Redis integration
WebSocket integration
multi-instance tests
persistence tests
failure tests
load-test infrastructure
```

## Antigravity

Primary ownership:

```text
frontend tests
Playwright
multi-browser tests
cursor tests
presence tests
UX error-state tests
deployed smoke tests
```

Both agents may review the other's tests.

---

# 75. Cross-Review Principle

For systems-heavy backend work:

```text
Codex implements
↓
Antigravity attempts to break via browser/client behavior
```

For frontend work:

```text
Antigravity implements
↓
Codex verifies contract and integration assumptions
```

---

# 76. Definition of Test Completion

Testing for a task is complete when:

* required deterministic tests pass,
* relevant regression tests exist,
* no required suite is skipped,
* no failure is hidden,
* acceptance criteria are verified,
* CI is green.

---

# 77. Testing Principle

The project uses the rule:

> **Tests decide whether implementation is correct; agent confidence does not.**

For collaboration specifically:

> **The final document state, durable history, and browser-observed behavior must all agree.**
