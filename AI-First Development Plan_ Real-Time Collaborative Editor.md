# AI-First Development Plan: Real-Time Collaborative Editor

**Project:** Real-Time Collaborative Editor  
**Primary stack:** Java, Spring Boot, React, TypeScript, PostgreSQL, Redis, native WebSockets, AWS

## Purpose

This document defines the high-level development strategy, phase order, AI-assisted workflow, and verification philosophy. It is not the source of truth for endpoint payloads, wire messages, database tables, or architecture decisions.

The governing order is the documentation hierarchy in `AGENTS.md`: project instructions first, accepted ADRs next, then product and technical contracts. Tool-specific agent rules and individual tasks must follow that hierarchy.

## 1. Development principles

- Build the collaboration system as a correctness-sensitive distributed system, not as a generic CRUD portfolio app.
- Keep the first synchronized model to plain text. Rich-text collaboration is deferred.
- Freeze shared contracts before backend and frontend implementations proceed independently.
- Keep synchronization logic independently testable from networking, persistence, Redis, and UI code.
- End every phase with a runnable project and executable verification.
- Establish benchmark tooling early, but report numbers only after reproducible runs.
- Prefer the simplest architecture that demonstrates the intended systems concepts.

## 2. Repository knowledge and authority

Agents must read the relevant repository documents rather than depend on chat history.

```text
AGENTS.md
    ↓
accepted ADRs
    ↓
PRODUCT_SPEC.md
    ↓
ARCHITECTURE.md and detailed contracts
    ↓
TESTING.md / BENCHMARKS.md
    ↓
development plan and task roadmap
    ↓
public README
```

`docs/API.md`, `docs/REALTIME_PROTOCOL.md`, and `docs/DATABASE.md` are shared contracts. A contract change is its own reviewed task; implementation work must not silently edit a contract to fit existing code.

## 3. Default ownership

### Codex — systems and integration

Default responsibilities:

- system architecture and shared contracts;
- Java/Spring Boot backend;
- authentication and authorization services;
- OT semantics and server integration;
- WebSocket server and reconnect coordination;
- PostgreSQL persistence and recovery;
- Redis coordination and multi-instance behavior;
- observability, load testing, AWS, and cross-cutting integration.

### Antigravity — frontend and browser verification

Default responsibilities:

- React/TypeScript frontend;
- editor integration and client state;
- client-side OT integration after semantics freeze;
- connection, save, presence, cursor, and history UX;
- frontend unit tests and Playwright;
- adversarial multi-browser, reconnect, and deployment smoke testing.

Ownership is a default, not an exemption from cross-review. The repository contracts, automated tests, and CI determine correctness.

## 4. Contract workflow

Shared behavior follows this sequence:

```text
owner proposes contract
        ↓
other implementation perspective challenges it
        ↓
unresolved correctness issues are recorded
        ↓
decision owner revises
        ↓
human approval when required
        ↓
contract freezes
        ↓
implementation may proceed in parallel
```

Parallelize implementation only after the shared interface is stable. Do not have separate agents independently design the same REST, WebSocket, persistence, or synchronization contract.

## 5. Closed-loop tasks

Each meaningful task specifies:

- owner and reviewer;
- dependencies and whether parallel work is safe;
- allowed and forbidden file scopes;
- controlling contracts;
- required behavior;
- executable verification;
- documentation updates;
- risks or explicit deferrals.

An implementation task continues through build, test, diagnosis, correction, and rerun until its acceptance criteria pass or a genuine contract blocker is found.

## 6. Phase plan

The detailed task sequence and status live in `docs/tasks/README.md`. These phases define the high-level order.

### Phase 0 — Architecture and specification

Deliver:

- accepted synchronization, WebSocket, persistence, Redis, and AWS ADRs;
- reviewed product, architecture, API, protocol, database, test, and benchmark documents;
- explicit tracking for unresolved contract blockers;
- a concrete implementation roadmap.

Verification:

- all repository Markdown is reviewed;
- documentation links and hierarchy are valid;
- contradictions are resolved or explicitly recorded;
- no benchmark target is presented as measured.

This phase does not implement application code.

### Phase 1 — Repository bootstrap, Docker, scripts, and CI

Deliver one runnable skeleton containing:

- Spring Boot backend with liveness/readiness;
- React/TypeScript frontend that can call a backend health endpoint;
- PostgreSQL and Redis local dependencies;
- Docker Compose;
- backend/frontend test skeletons;
- stable start and test script entry points;
- initial GitHub Actions jobs;
- empty benchmark artifact structure and workload-harness skeleton.

End state: the full stack starts, dependencies report healthy, the browser reaches the backend, and the baseline test command exits successfully.

### Phase 2 — Authentication vertical slice

First freeze the open account validation, token-format, and schema decisions. Then implement registration, login, refresh, logout, current-user loading, protected frontend state, and authentication tests.

End state: the project remains runnable and an automated browser can register, sign in, refresh, access a protected route, and sign out.

### Phase 3 — Documents, sharing, and initial persistence

Freeze exact document-field/schema limits and migrations. Implement document create/list/open/rename/delete, owner/editor permissions, sharing/revocation, authorization tests, and a revision-0 snapshot for every new document.

End state: authenticated users can complete document and sharing workflows through API and browser, and data survives an application restart.

### Phase 4 — Plain-text editor shell

Implement the local plain-text editing interface, title editing, loading/error states, keyboard behavior, and local editor tests. Do not advertise synchronized formatting.

End state: a user can load and edit one document locally in the browser while the collaborative body still awaits the real-time pipeline.

### Phase 5 — OT core and shared vectors

Before multi-operation client integration, resolve the causal pending-operation model. Complete the server-generated composite semantics required by insert-wins, and separately decide whether client-authored `GROUP` is enabled or deferred. Then implement isolated Java and TypeScript OT modules with shared language-neutral vectors, property tests, Unicode/UTF-16 cases, and multi-client simulations.

End state: deterministic tests prove supported operations converge independently of networking or persistence; the rest of the app remains runnable.

### Phase 6 — Durable operation pipeline

Implement canonical operation batching, idempotency rows, conditional revision advancement, snapshots, reconstruction, and failure injection. Integrate the server-side OT module through a service boundary without WebSocket transport.

End state: service/integration tests prove operations commit atomically, retries are idempotent, initial and periodic snapshots recover exact content, and failed commits receive no durable success.

### Phase 7 — Single-server real-time collaboration

Freeze remaining public protocol details, including close codes and limits. Implement real-time tickets, native WebSocket lifecycle, hello/catch-up/ready, canonical operation delivery plus acknowledgements, bounded queues, and same-epoch reconnect.

End state: two browser contexts edit concurrently through one backend, receive only durably committed operations, reconnect, and converge.

### Phase 8 — Presence, cursors, and selections

Implement ephemeral connection-based presence, cursor/selection routing, rate limits, aggregation, disconnect cleanup, and browser rendering.

End state: at least three browser contexts show correct presence and remote positions without cursor traffic delaying document operations.

### Phase 9 — Version history and restore

Before connected restore, resolve how unacknowledged local intent is preserved when the epoch changes. Implement checkpoint creation/list/detail, pre-restore protection, atomic new-epoch restore, client reset behavior, and history UI.

End state: automated tests create, inspect, and restore history; all connected clients resynchronize; old-epoch edits are rejected without silent user-data loss.

### Phase 10 — Redis leadership and cross-instance collaboration

Implement lease acquire/renew/release, non-leader ingress, post-commit Pub/Sub, subscriptions, gap recovery, distributed tickets/presence, and multi-instance integration infrastructure.

End state: clients intentionally connected to different backend instances edit the same document and converge; correctness does not depend on sticky sessions.

### Phase 11 — Failover and distributed recovery

Add deterministic failure and chaos scenarios for leader loss, stale leaders, missing/duplicate events, Redis restart, backend termination, reconnect storms, and PostgreSQL unavailability.

End state: committed edits are neither lost nor duplicated, canonical revisions remain valid, and the app recovers or degrades visibly.

### Phase 12 — Observability and load harness

Implement structured logging, request/connection/operation/persistence/Redis/database/JVM metrics, traceable operation timing, and correctness-aware load scenarios.

End state: a small load run produces machine-readable metadata and raw metrics while verifying accepted counts, revision continuity, and final checksums. Its numbers are not yet public claims.

### Phase 13 — Baseline benchmarks

Run and preserve the official connection, collaboration, contention, multi-document, persistence, long-history, recovery, and multi-instance baselines defined in `docs/BENCHMARKS.md`.

End state: each reported result identifies code revision, environment, workload, raw artifacts, errors, and correctness verification. Targets remain labeled as targets when missed or untested.

### Phase 14 — AWS infrastructure and deployment

Select the infrastructure-as-code tool and external edge-routing variant before implementation. Deploy static frontend hosting, load-balanced ECS Fargate tasks, private RDS and ElastiCache, secrets, logs/metrics, migrations, health checks, and CI/CD.

End state: production/staging smoke tests cover the full user journey, prove same-document collaboration across at least two backend tasks, and record the actual deployed configuration and cost-conscious choices.

### Phase 15 — Evidence-driven optimization

Profile a recorded baseline, change one primary bottleneck, rerun the identical benchmark, retain only changes that preserve correctness, and store before/after evidence.

End state: any README or resume metric is linked to reproducible artifacts. Unverified targets are never rewritten as accomplishments.

## 7. Why this order

The original conceptual sequence placed WebSocket collaboration before OT and durable persistence. That conflicts with the accepted contracts: a canonical operation needs OT semantics, and a successful acknowledgement means PostgreSQL has committed it. The revised sequence therefore builds and proves OT and persistence before the WebSocket collaboration milestone.

Redis multi-instance work follows a correct single-server path so distributed failures can be distinguished from synchronization bugs. Observability precedes official baselines, and baselines precede optimization.

## 8. Verification model

The verification stack grows with the project:

```text
format / lint / typecheck
        ↓
unit and OT property tests
        ↓
PostgreSQL and Redis integration tests
        ↓
WebSocket and multi-instance tests
        ↓
frontend component tests
        ↓
Playwright multi-browser tests
        ↓
failure and recovery tests
        ↓
correctness-aware benchmarks
        ↓
deployed smoke tests
```

Each phase adds its suites to the canonical local and CI commands. Expensive full benchmarks may run separately, but required functional suites cannot be skipped.

## 9. Git and parallel work

When multiple agents work simultaneously, use separate branches and writable worktrees. One agent owns a task; the other reviews or implements a dependency-independent task against a frozen contract.

Agents may autonomously read, edit in scope, compile, test, use local containers, benchmark, inspect logs, and create local commits. Human approval remains required for merges, force pushes, production data or infrastructure mutations, secrets/security-policy changes, expensive cloud changes, and frozen-architecture changes.

## 10. Human architect role

The human developer approves important architecture, prioritizes tasks, resolves agent disagreements, reviews contract changes and benchmark evidence, and understands the decisions well enough to explain:

- why server-authoritative OT was selected;
- how concurrent edits converge;
- what happens across disconnect, retry, leader loss, and Redis loss;
- why PostgreSQL and Redis have different ownership;
- when an edit becomes durable;
- how multi-instance collaboration and AWS routing work;
- how every published performance claim was measured.

AI can perform most mechanical implementation, testing, debugging, and documentation, but it must preserve developer understanding and explicit approval boundaries.

## 11. Operating principle

> Codex owns system coherence. Antigravity owns client reality. Shared contracts connect them. Automated tests and benchmark evidence decide whether the implementation is correct.
