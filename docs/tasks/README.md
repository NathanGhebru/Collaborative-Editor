# Implementation Roadmap

## Purpose

This file tracks implementation order, dependencies, task status, and verification gates. It does not replace product, architecture, protocol, API, database, testing, or benchmark contracts.

## Current state

- Current phase: Phase 6 — Durable operation pipeline complete
- Application code: BOOT-001 skeleton complete; AUTH-001 contract frozen; AUTH-002 backend auth complete; AUTH-003 browser auth complete; DOC-001 contract frozen; DOC-002 document backend complete; DOC-003 document UI complete; EDIT-001 local editor complete; OT-001 synchronization contract frozen; OT-002 Java and TypeScript OT engines complete and verified; PERS-001 canonical operation persistence complete; PERS-002 durable OT sequencing complete
- Measured benchmarks: none
- Next task: `RT-001` (Freeze remaining public protocol details)

Status values are `Not Started`, `In Progress`, `Blocked`, and `Complete`. A task becomes `Complete` only after its listed verification succeeds and required documentation is updated.

## Sequence rules

1. Work phases in order unless a task explicitly says it is parallel-safe.
2. Resolve decision tasks before dependent implementation; do not choose unresolved protocol behavior inside application code.
3. Keep the project runnable and its previously completed suites passing at every phase gate.
4. Add each new suite to `./scripts/test-all.sh` and CI when its phase completes.
5. Targets in load tasks are not results. Save actual artifacts before reporting metrics.

## Phase overview

| Phase | Outcome | Gate |
| ---: | --- | --- |
| 0 | Coherent reviewed documentation and explicit open decisions | Documentation audit complete |
| 1 | Runnable backend/frontend with PostgreSQL, Redis, scripts, and CI | Start and smoke tests pass |
| 2 | Working authenticated browser session | Auth API and E2E pass |
| 3 | Durable document/sharing vertical slice | API, database, authorization, and browser tests pass |
| 4 | Runnable local plain-text editor | Frontend editor tests pass |
| 5 | Independently proven OT primitives | Shared Java/TypeScript vectors and property tests pass |
| 6 | Durable canonical operation service | Persistence and recovery tests pass |
| 7 | Correct single-server collaboration | Two-browser concurrency/reconnect tests pass |
| 8 | Presence and remote cursors | Three-browser tests pass |
| 9 | Version history and safe restore | Restore/reset/recovery tests pass |
| 10 | Cross-instance collaboration through Redis | Two-instance tests pass |
| 11 | Verified distributed failure recovery | Chaos and failover tests pass |
| 12 | Observable, correctness-aware load harness | Small instrumented load test passes |
| 13 | Reproducible baseline evidence | Official artifacts recorded |
| 14 | Reproducible AWS deployment | Distributed production smoke test passes |
| 15 | Evidence-backed optimization | Comparable before/after artifacts recorded |

## Task verification index

These are the task-specific commands or evidence gates. The scripts are created by `BOOT-001`; later tasks must keep their interface working or update this roadmap and testing documentation deliberately.

| Task | Required verification |
| --- | --- |
| `PLAN-001` | UTF-8, relative-link, Markdown-fence, ADR-section, and contradiction scans pass |
| `BOOT-001` | `docker compose up --build`; `./scripts/test-all.sh` |
| `AUTH-001` | `./scripts/validate-docs.sh` plus reviewed API/schema/security matrix |
| `AUTH-002` | `./scripts/test-backend.sh -- auth`; `./scripts/test-integration.sh -- auth` |
| `AUTH-003` | `./scripts/test-frontend.sh -- auth`; `./scripts/test-e2e.sh -- auth` |
| `DOC-001` | `./scripts/validate-docs.sh` plus reviewed endpoint/schema/permission matrix |
| `DOC-002` | `./scripts/test-backend.sh -- documents`; `./scripts/test-persistence.sh -- documents` |
| `DOC-003` | `./scripts/test-frontend.sh -- documents`; `./scripts/test-e2e.sh -- documents sharing` |
| `EDIT-001` | `./scripts/test-frontend.sh -- editor`; `./scripts/test-e2e.sh -- editor` |
| `OT-001` | `./scripts/validate-docs.sh`; human approval of synchronization contract |
| `OT-002` | `./scripts/test-ot.sh`; shared Java/TypeScript vector equality |
| `PERS-001` | `./scripts/test-persistence.sh -- operation-log snapshots recovery` |
| `PERS-002` | `./scripts/test-sequencing.sh` |
| `RT-001` | `./scripts/validate-docs.sh`; protocol schema/close/limit review |
| `RT-002` | `./scripts/test-websocket.sh -- lifecycle operations authorization` |
| `RT-003` | `./scripts/test-websocket.sh -- reconnect`; `./scripts/test-e2e.sh -- collaboration reconnect` |
| `PRES-001` | `./scripts/test-websocket.sh -- presence cursors`; `./scripts/test-e2e.sh -- presence cursors` |
| `HIST-001` | `./scripts/validate-docs.sh`; human approval of epoch-change recovery behavior |
| `HIST-002` | `./scripts/test-persistence.sh -- versions`; `./scripts/test-e2e.sh -- version-history` |
| `DIST-001` | `./scripts/test-redis.sh -- leadership tickets presence` |
| `DIST-002` | `./scripts/test-multi-instance.sh -- collaboration`; `./scripts/test-e2e.sh -- multi-instance` |
| `FAIL-001` | `./scripts/test-multi-instance.sh -- failure-recovery`; `./scripts/test-e2e.sh -- failure-recovery` |
| `OBS-001` | `./scripts/test-integration.sh -- observability`; log redaction and metric assertions |
| `LOAD-001` | `./scripts/benchmark-small.sh`; artifact schema and correctness gates pass |
| `PERF-001` | `./scripts/benchmark-full.sh`; official artifact review passes |
| `AWS-001` | infrastructure plan/validation command selected by the IaC decision; `./scripts/validate-docs.sh` |
| `AWS-002` | IaC apply output, health checks, and deployed multi-instance smoke suite pass |
| `OPT-001` | Identical baseline/optimized benchmark commands; saved correctness and comparison artifacts |

---

## Phase 0 — Architecture and specification

### PLAN-001: Audit and align repository documentation

**Status:** Complete  
**Depends on:** None  
**Parallel safe:** No

Requirements:

- read every Markdown document;
- enforce the `AGENTS.md` hierarchy and file purposes;
- resolve minor contradictions without changing application code;
- record fundamental conflicts and missing correctness decisions;
- make targets and measured results unmistakable;
- publish this implementation sequence.

Verification:

- all internal Markdown links resolve;
- all Markdown files can be decoded as UTF-8;
- no current benchmark is presented as measured;
- architecture, API, protocol, and database responsibilities do not duplicate detailed contracts unnecessarily.

Phase exit: `PLAN-001` complete. Unresolved decisions may remain only when assigned to a later decision task before the affected implementation.

---

## Phase 1 — Repository bootstrap

### BOOT-001: Create the runnable project skeleton

**Status:** Complete

**Depends on:** `PLAN-001`  
**Parallel safe:** Backend and frontend scaffolding may proceed in separate worktrees after tool/version choices are recorded.

Requirements:

- initialize the Spring Boot backend and React/TypeScript frontend;
- expose backend liveness/readiness and a frontend smoke page;
- add PostgreSQL and Redis to Docker Compose with health checks;
- add repeatable local start/stop and canonical test scripts;
- add backend/frontend test skeletons and one cross-stack smoke test;
- add initial GitHub Actions build, typecheck, and unit-test jobs;
- add empty benchmark artifact directories and a load-harness skeleton without results;
- document actual prerequisites and commands in `README.md`.

Verification:

```bash
docker compose up --build
./scripts/test-all.sh
```

Acceptance: services become healthy, the frontend reaches the backend, all starter checks pass, and a clean clone can follow the documented setup.

Verification note (2026-08-29): the canonical `docker compose up --build --wait` and `./scripts/test-all.sh` gates pass. PostgreSQL and Redis report healthy; direct PostgreSQL/Redis checks pass; Spring Boot reports `UP`; the frontend development proxy and cross-stack smoke test reach backend health successfully; and the canonical start/stop lifecycle scripts clean up containers after application listeners are stopped.

Phase exit: the repository is runnable and testable even though product features are not implemented.

---

## Phase 2 — Authentication

### AUTH-001: Freeze authentication API and schema details

**Status:** Complete

**Depends on:** `BOOT-001`  
**Parallel safe:** No

Requirements:

- resolve open account validation, normalization, token-format, signing/key-management, cookie, rate-limit, and migration decisions in `API.md` and `DATABASE.md`. Add an ADR only if the decision is architecturally significant.

Verification:

- `./scripts/validate-docs.sh` passes; contract examples, status mappings, schema constraints, and security tests form a consistent matrix.

Verification note (2026-08-29): authentication endpoints, request/response formats, validation regexes, token/cookie settings, status codes, and rate limits frozen in `docs/API.md` Section 35. PostgreSQL `users` and `refresh_tokens` tables, column limits, SHA-256 token hashing, and Flyway migration path frozen in `docs/DATABASE.md` Sections 4 and 5. `./scripts/validate-docs.sh` verified.

### AUTH-002: Implement backend authentication

**Status:** Complete

**Depends on:** `AUTH-001`  
**Parallel safe:** Yes, with `AUTH-003` after the contract freezes.

Requirements:

- implement registration, login, refresh rotation, logout/revocation, current-user loading, password hashing, persistence, authorization middleware, and backend/integration/security tests.

Verification:

- `./scripts/test-backend.sh` passes; backend unit tests and controller integration tests pass cleanly.

Verification note (2026-08-29): Flyway schema migration `V1__init_auth_schema.sql` created for `users` and `refresh_tokens`. JPA entities, repositories, BCrypt password encoder, JJWT access token service, SHA-256 refresh token rotation, Spring Security filter chain, `@RestControllerAdvice` global exception handling, and `/api/v1/auth/*` and `/api/v1/users/me` REST controllers implemented. Unit (`AuthServiceTest`) and integration (`AuthControllerTest`) suites pass. `./scripts/test-backend.sh` verified.

### AUTH-003: Implement browser authentication

**Status:** Complete
**Depends on:** `AUTH-001`  
**Parallel safe:** Yes, with `AUTH-002`.

Implement account screens, protected routes, access-credential state, refresh behavior, visible errors, and Playwright flows.

Phase verification:

```bash
./scripts/test-backend.sh
./scripts/test-frontend.sh
./scripts/test-e2e.sh -- auth
```

Phase exit: a browser can register, log in, refresh, access a protected route, and log out; invalid and revoked credentials fail correctly.

---

## Phase 3 — Documents, sharing, and initial snapshots

### DOC-001: Freeze document API and schema limits

**Status:** Complete

**Depends on:** `AUTH-001`  
**Parallel safe:** No

Requirements:

- finalize document/title/content/version-label limits, pagination cursors, schema types/constraints, and initial-snapshot transaction behavior.

Verification:

- `./scripts/validate-docs.sh` passes; reviewed endpoint/schema/permission matrix verified.

Verification note (2026-08-29): Document summary/detail representations, CRUD endpoints (`POST`, `GET`, `PATCH`, `DELETE` `/api/v1/documents`), sharing endpoints (`GET`, `POST`, `DELETE` `/api/v1/documents/{id}/permissions`), title validation regex (`^[^\r\n]{1,255}$`), content size limit (1MB / 1,000,000 UTF-16 code units), cursor pagination (`updatedAt DESC, id DESC`), unauthorized resource concealment policy (`404 DOCUMENT_NOT_FOUND`), and error codes frozen in `docs/API.md` Sections 12–22. Database schema for `documents`, atomic revision-0 `document_snapshots`, `document_permissions` table, indexes, foreign keys, cascade rules, and Flyway migration location `V2__init_document_schema.sql` frozen in `docs/DATABASE.md` Sections 6–8. `./scripts/validate-docs.sh` verified.

### DOC-002: Implement document and sharing backend

**Status:** Complete

**Depends on:** `AUTH-002`, `DOC-001`  
**Parallel safe:** Yes, with `DOC-003` after the API freezes.

Requirements:

- implement create/list/open/rename/hard-delete, owner/editor authorization, grant/revoke access, revision-0 snapshots for empty and supplied initial text, migrations, and real PostgreSQL integration tests.

Verification:

- `./scripts/test-backend.sh` passes; backend unit tests and controller integration tests pass cleanly.

Verification note (2026-08-29): Flyway schema migration `V2__init_document_schema.sql` created for `documents`, `document_snapshots`, and `document_permissions`. JPA entities, repositories, `DocumentService`, `DocumentController`, cursor pagination encoder/decoder, atomic revision-0 snapshot creation, and permission management implemented. Unit (`DocumentServiceTest`) and integration (`DocumentControllerTest`) suites pass. `./scripts/test-backend.sh` and `./scripts/validate-docs.sh` verified.

### DOC-003: Implement document and sharing UI

**Status:** Complete

**Depends on:** `AUTH-003`, `DOC-001`  
**Parallel safe:** Yes, with `DOC-002`.

Requirements:

- implement document list/create/open/rename/delete/share/revoke flows, denied-access behavior, and Playwright coverage.

Verification:

- `./scripts/test-frontend.sh -- documents`; `./scripts/test-e2e.sh -- documents` pass.

Verification note (2026-08-29): React documents dashboard (`DocumentsDashboard.tsx`), document detail view with owner/editor permissions management (`DocumentDetailPage.tsx`), document API client (`frontend/src/documents/api.ts`), and unit/E2E test suites (`DocumentsFlow.test.tsx`, `frontend/e2e/documents.spec.ts`) implemented and verified against the frozen DOC-001 contract. Combined frontend and backend flows pass cleanly.

Phase verification:

```bash
./scripts/test-integration.sh
./scripts/test-persistence.sh
./scripts/test-e2e.sh -- documents sharing
```

Phase exit: API and browser workflows persist across application restart, and unauthorized direct access fails.

---

## Phase 4 — Plain-text editor shell

### EDIT-001: Implement the local editor experience

**Status:** Complete  
**Depends on:** `DOC-002`, `DOC-003`  
**Parallel safe:** Primarily frontend-owned.

Implement multiline plain-text editing, title editing, selection/cursor capture, loading/error state, keyboard accessibility, and local operation extraction. Do not implement or advertise collaborative formatting.

Verification:

```bash
./scripts/test-frontend.sh
./scripts/test-e2e.sh -- editor
```

Verification note (2026-08-30): Integrated Antigravity's production implementation with Codex's independent acceptance tests (`EditorAcceptance.test.tsx` and combined E2E tests in `editor.spec.ts`). Fixed cascading delete in H2 test database and stabilized E2E race conditions. All 28 frontend tests, 49 backend tests, and 12 Playwright E2E tests pass cleanly.

Phase exit: the full app remains runnable and a user can load and edit document text locally with deterministic operation capture.

---

## Phase 5 — OT core

### OT-001: Resolve OT protocol blockers

**Status:** Complete  
**Depends on:** `EDIT-001`  
**Parallel safe:** No

Decide and document:

- the causal submission model for more than one local pending edit;
- complete server-generated composite transformation semantics required by insert-wins;
- whether client-authored `GROUP` is enabled or explicitly deferred;
- canonical shared vector format and ordering rules for the chosen model.

Update ADR-001, `REALTIME_PROTOCOL.md`, architecture, and tests. Human approval is required because this freezes synchronization semantics.

Verification note (2026-08-30): Single in-flight + sequential local buffer client rebase model frozen. Server-emitted sequential GROUP composites and split DELETE math under insert-wins policy frozen. Client-authored GROUP explicitly deferred for v1. Canonical 23-vector JSON suite created and verified in `docs/ot-test-vectors.json`. ADR-001 Sections 6, 9, 18, 19, 21, 23, 24, 38 and `REALTIME_PROTOCOL.md` Sections 15–17, 27–31, 48 updated and approved. `./scripts/validate-docs.sh` verified.

### OT-002: Implement primitive OT modules and shared vectors

**Status:** Complete  
**Depends on:** `OT-001`  
**Parallel safe:** Java and TypeScript implementations may proceed in parallel against immutable shared vectors.

Implement UTF-16 `INSERT`, `DELETE`, `NO_OP`, required server-generated composites, chosen pending behavior, pairwise transformations, randomized property tests, and deterministic 3/10/50-client simulations. Accept client-authored `GROUP` only if `OT-001` enables it.

Verification:

```bash
./scripts/test-ot.sh
./scripts/test-backend.sh
./scripts/test-frontend.sh
```

Verification note (2026-08-30): Fully integrated and verified both Java (`antigravity/ot-002-server`) and TypeScript (`codex/ot-002-client`) OT cores against the frozen OT-001 specification and `docs/ot-test-vectors.json`. All 23 canonical test vectors pass identically in both languages (transform A, transform B, A then B', B then A', and document convergence, plus three-step pending queue rebase). Validated UTF-16 code units, surrogate-pair bisection protection, same-position tie-breaking by lowercase UUID `OperationKey`, insert-wins delete splitting, group flattening, TP1 property/invariant suites, and 3/10/50-client convergence simulations. Unified `./scripts/test-ot.sh` to test both backend and frontend suites. All 98 backend tests, 71 frontend tests, and 10 Playwright E2E tests pass cleanly.

Phase exit: both languages pass the same vectors and convergence simulations without networking.

---

## Phase 6 — Durable operation pipeline

### PERS-001: Implement canonical operation persistence

**Status:** Complete  
**Depends on:** `DOC-001`, `OT-002`  
**Parallel safe:** No

Implement operation batches (`document_operation_batches`), operation-identity rows (`document_operation_ids`), conditional revision fencing (`StaleRevisionFencingException`), snapshot-plus-log recovery (`OperationPersistenceService`), and gap detection (`RevisionGapException`) adhering to ADR-003 and `DATABASE.md`.

Verification:

```bash
./scripts/test-persistence.sh
./scripts/test-backend.sh
```

Verification note (2026-08-30): Integrated Antigravity's production persistence implementation (`antigravity/pers-001`) with Codex's independent PostgreSQL 17 Testcontainers acceptance suite (`codex/pers-001-tests`). Verified atomic batch commits, conditional revision fencing, durable operation identity lookup/idempotency, JSONB operation serialization (INSERT, DELETE, NO_OP, GROUP, UTF-16 emojis), snapshot-plus-log document recovery, and ON DELETE CASCADE hard deletion. All 14 production persistence tests, all 10 PostgreSQL Testcontainers acceptance tests, all 112 backend tests, all 42 frontend OT tests, and 71 total frontend tests pass cleanly. Extended `./scripts/test-persistence.sh` to support `--fast`, `--postgres`, and combined execution.

### PERS-002: Integrate OT sequencing with durable acceptance

**Status:** Complete  
**Depends on:** `PERS-001`  
**Parallel safe:** No

Implement transport-independent `DocumentSequencingService` coordinating envelope validation, document/epoch verification, base revision validation, historical rebase against committed canonical history via `OtEngine`, deterministic UUID tie-breaking, split deletion / composite group handling, optimistic sequencing retry loop with conditional revision fencing, durable persistence via `OperationPersistenceService`, and exact idempotent retry.

Verification:

```bash
./scripts/test-sequencing.sh
./scripts/test-persistence.sh
./scripts/test-backend.sh
```

Verification note (2026-08-30): Integrated Antigravity's production sequencing pipeline (`antigravity/pers-002`) with Codex's independent PostgreSQL 17 Testcontainers acceptance suite (`codex/pers-002-tests`). Verified document recovery, snapshot uniqueness across sync epochs (`UNIQUE (document_id, sync_epoch, revision)` via Flyway migration V4), multi-operation rebase, server-generated composite group / split delete persistence, idempotency lookup & conflict detection on identity reuse, pre-commit persistence failure rollback, and optimistic fencing retry. Created `TestSequencingAdapterFactory` bridging Spring context to `DurableSequencingTestAdapter` test harness and created `scripts/test-sequencing.sh`. All 11 production sequencing tests, all 12 PostgreSQL Testcontainers acceptance tests (23 total sequencing tests), all 24 persistence tests, all 123 backend tests, and all 42 TypeScript OT tests pass cleanly.

---

## Phase 7 — Single-server real-time collaboration

### RT-001: Freeze remaining public protocol details

**Status:** Complete  
**Depends on:** `OT-001`  
**Parallel safe:** No

Finalize numeric close codes, reconnect categories, concrete frame/operation/document/pending/rate limits, and all event requirements. Freeze `REALTIME_PROTOCOL.md` before implementation.

Verification note (2026-08-30): Frozen public real-time collaboration protocol v1. Finalized REST ticket acquisition DTO (`POST /api/v1/documents/{documentId}/realtime-ticket`), 60s TTL, single-use consumption, query param `?ticket=<ticket>`, HTTP access log redaction requirements, and HTTP handshake upgrade failure (401/404) vs. WebSocket post-handshake close distinction. Defined `clientId` tab lifecycle (UUID v4, reconnect persistence, `client.hello` binding, duplicate session replacement via `4004 SESSION_SUPERSEDED`), UTF-8 JSON text framing, common envelope fields (`protocolVersion: 1`, `type`, `messageId`, `documentId`, `syncEpoch`, `clientId`, `timestamp`), minimal ready sequence (`client.hello` $\rightarrow$ catch-up `server.operations` $\rightarrow$ `server.ready`), Single-Stream Delivery & Acknowledgement model using `server.operations` for both remote broadcast and origin client ACK, rejection of client-authored `GROUP`/`NO_OP`, surrogate-pair UTF-16 position bounds (`INVALID_POSITION`), canonical `NO_OP` revision consumption, authoritative close codes table (RFC 6455 1000–1011 + 4000–4004 application range with zero collisions), comprehensive 21-row error matrix, presence/cursor deferral (`PRES-001`), explicit numerical limits (64 KB max frame, 10,000 UTF-16 code units max insert, 1,000,000 UTF-16 code units max document size), and created `docs/realtime-protocol-fixtures.json`. Verified via `./scripts/validate-docs.sh` and python JSON fixture validation.

### RT-002: Implement tickets and WebSocket collaboration

**Status:** Not Started  
**Depends on:** `AUTH-002`, `PERS-002`, `RT-001`  
**Parallel safe:** Server and client work may proceed in parallel after the protocol freezes.

Implement ticket creation/consumption, socket authorization, hello/catch-up/presence-snapshot/ready lifecycle, operation routing to the durable service, canonical delivery then acknowledgement, bounded queues, and structured errors.

### RT-003: Implement same-epoch reconnect and recovery tests

**Status:** Not Started  
**Depends on:** `RT-002`  
**Parallel safe:** Client work and adversarial browser tests may proceed together.

Implement retry with stable operation IDs, exponential backoff with jitter, gap catch-up, already-accepted detection, and visible reconnect/save state for cases where incremental rebase is safe.

Phase verification:

```bash
./scripts/test-websocket.sh
./scripts/test-e2e.sh -- collaboration reconnect
```

Phase exit: two browsers concurrently edit through one server, receive only committed operations, reconnect, and converge.

---

## Phase 8 — Presence and cursors

### PRES-001: Implement ephemeral collaboration awareness

**Status:** Not Started  
**Depends on:** `RT-002`  
**Parallel safe:** Backend routing and frontend rendering may proceed in parallel against the frozen message schemas.

Implement connection-based presence, aggregation, heartbeat/TTL cleanup, cursor/selection transformation and rendering, rate limiting, queue priority, and disconnect/reconnect tests.

Verification:

```bash
./scripts/test-websocket.sh
./scripts/test-e2e.sh -- presence cursors
```

Phase exit: three browser contexts show correct users, cursors, selections, and cleanup while document editing remains responsive.

---

## Phase 9 — Version history and restore

### HIST-001: Resolve old-epoch pending-intent recovery

**Status:** Not Started  
**Depends on:** `RT-003`  
**Parallel safe:** No

Define the protocol and UX for preserving unacknowledged user intent when restore or another event changes the epoch and automatic rebase is unsafe. Update ADR-001, product behavior, real-time protocol, and deterministic tests.

### HIST-002: Implement version history and restore

**Status:** Not Started  
**Depends on:** `PERS-002`, `HIST-001`  
**Parallel safe:** Backend API and frontend history UI may proceed in parallel after contracts freeze.

Implement checkpoint creation/list/detail, labels/reasons, pre-restore protection, optimistic restore conflict, atomic new-epoch revision-0 snapshot, reset propagation, and recovery UI.

Verification:

```bash
./scripts/test-persistence.sh
./scripts/test-websocket.sh
./scripts/test-e2e.sh -- version-history
```

Phase exit: history survives restart; restore retains prior history; all clients reset safely; old-epoch operations cannot corrupt the new timeline.

---

## Phase 10 — Redis and multi-instance collaboration

### DIST-001: Implement Redis coordination primitives

**Status:** Not Started  
**Depends on:** `RT-002`  
**Parallel safe:** No

Implement atomic leader acquire/renew/release, loss-of-lease behavior, single-use distributed tickets, presence TTL state, scoped subscriptions, and real Redis integration tests.

### DIST-002: Implement cross-instance operation routing

**Status:** Not Started  
**Depends on:** `DIST-001`, `PRES-001`  
**Parallel safe:** Browser verification may proceed against a fixed internal schema.

Implement non-leader ingress, local-leader optimization, post-commit accepted-event Pub/Sub, duplicate suppression, revision-gap recovery, and two-instance test routing.

Verification:

```bash
./scripts/test-redis.sh
./scripts/test-multi-instance.sh
./scripts/test-e2e.sh -- multi-instance
```

Phase exit: clients pinned to different backend instances edit one document, see presence/cursors, and converge without sticky-session correctness.

---

## Phase 11 — Failure recovery

### FAIL-001: Verify distributed failure behavior

**Status:** Not Started  
**Depends on:** `DIST-002`, `HIST-002`  
**Parallel safe:** Test scenarios may be developed independently after failure contracts freeze.

Cover leader kill/takeover, stale-leader fencing, backend kill, Redis restart, dropped/duplicated/out-of-order Pub/Sub, database failure, commit-before-ack crash, slow clients, and reconnect storms.

Verification:

```bash
./scripts/test-multi-instance.sh
./scripts/test-persistence.sh
./scripts/test-e2e.sh -- failure-recovery
```

Phase exit: no committed edit is lost or duplicated, revisions remain gap-free, and unsafe dependencies produce visible degraded behavior.

---

## Phase 12 — Observability and load harness

### OBS-001: Implement production-style telemetry

**Status:** Not Started  
**Depends on:** `FAIL-001`  
**Parallel safe:** Backend telemetry and browser timing instrumentation may proceed in parallel.

Implement structured safe logs, request and connection IDs, operation-stage timestamps, HTTP/WebSocket/OT/persistence/Redis/database/JVM metrics, and local metric inspection.

### LOAD-001: Implement correctness-aware load scenarios

**Status:** Not Started  
**Depends on:** `OBS-001`  
**Parallel safe:** Scenario files may be partitioned by workload.

Implement connection, single-document, multi-document, reconnect, presence, persistence, long-history, and failover workloads with metadata capture, accepted-count checks, revision checks, and final checksums.

Verification:

```bash
./scripts/benchmark-small.sh
```

Phase exit: a small non-claim run emits complete machine-readable artifacts and passes correctness gates.

---

## Phase 13 — Baseline benchmarks

### PERF-001: Record official baselines

**Status:** Not Started  
**Depends on:** `LOAD-001`  
**Parallel safe:** Independent workload categories may run separately on a controlled environment.

Run the official benchmark matrix with repetitions and warm-up. Record code revision, workload manifest, environment, raw client/server/database/Redis metrics, variance, errors, and final convergence.

Verification:

```bash
./scripts/benchmark-full.sh
```

Phase exit: `docs/BENCHMARKS.md` and benchmark artifacts distinguish achieved results from missed/untested targets. No target is promoted without evidence.

---

## Phase 14 — AWS deployment

### AWS-001: Freeze deployment implementation choices

**Status:** Not Started  
**Depends on:** `PERF-001`  
**Parallel safe:** No

Select Terraform or AWS CDK, choose CloudFront-to-ALB versus separate API/WS hostname, record actual region/environment/cost choices, and update ADR-005 if any accepted architecture changes.

### AWS-002: Deploy and verify the AWS architecture

**Status:** Not Started  
**Depends on:** `AWS-001`  
**Parallel safe:** Infrastructure and smoke-test work may proceed against frozen deployment interfaces.

Implement S3/CloudFront, ECS Fargate/ALB, ECR, private RDS/ElastiCache, Secrets Manager, CloudWatch, migrations, backups, rolling deployment, CI/CD, and cost alerts.

Verification:

- production smoke suite passes over HTTPS/WSS;
- at least two ECS tasks participate in same-document collaboration;
- one task can terminate and clients recover;
- deployed configuration and any AWS benchmark environment are recorded accurately.

Phase exit: the complete product journey works in the documented deployment without public database/Redis access or repository secrets.

---

## Phase 15 — Evidence-driven optimization

### OPT-001: Profile and optimize one measured bottleneck at a time

**Status:** Not Started  
**Depends on:** `PERF-001`; AWS-specific optimization also depends on `AWS-002`  
**Parallel safe:** Independent backend and browser bottlenecks may be investigated separately with isolated experiments.

For each optimization:

1. select a saved baseline;
2. profile and identify one bottleneck;
3. change one primary variable;
4. rerun the identical workload;
5. verify correctness and regression limits;
6. retain or revert based on evidence;
7. store comparison artifacts and update public claims only when justified.

Phase exit: every stated improvement links to reproducible before/after evidence; unmeasured goals remain targets.

## Next task

`OT-001` (Resolve OT protocol blockers) is Complete and synchronization contracts are frozen. `OT-002` (Implement primitive OT modules and shared vectors) is the next task in sequence. Do not begin persistence integration, WebSocket, or Redis coordination before OT-002 verification passes.
