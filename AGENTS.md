# AGENTS.md

## Project Goal

This repository contains a resume-quality real-time collaborative document editor.

The project is intended to demonstrate:

* distributed systems concepts
* real-time communication
* WebSocket architecture
* concurrency and synchronization
* database design and persistence
* Redis-based coordination
* scalable backend architecture
* AWS deployment
* load testing
* measurable performance engineering
* production-style testing and observability

The technically distinguishing feature of the project is real-time collaborative editing. Do not turn this into a generic CRUD application or focus primarily on rich-text editor features.

The project should remain understandable and defensible by a single developer in a software engineering interview.

---

# General Instructions

Before making significant changes:

1. Read the relevant documentation listed below.
2. Understand the current architecture before modifying it.
3. Follow existing architectural decisions unless the task explicitly requires reconsidering them.
4. Do not silently change APIs, database schemas, synchronization behavior, or architectural contracts.
5. Prefer the simplest implementation that correctly satisfies the documented requirements.
6. Avoid unnecessary frameworks, abstractions, infrastructure, and dependencies.
7. Do not add features simply to make the project larger.
8. Keep frontend, backend, persistence, synchronization, and infrastructure responsibilities clearly separated.
9. Add or update tests whenever behavior changes.
10. Run relevant tests after implementation.
11. Update the corresponding documentation when implementation decisions change.
12. Never invent performance or scalability metrics.
13. Only report benchmark numbers that were actually measured by reproducible tests.
14. Preserve benchmark methodology so resume metrics can later be defended and reproduced.
15. When requirements conflict, flag the conflict and resolve it using the documentation hierarchy defined below.

---

# Parallel Agent Development

Multiple AI agents may work on this repository concurrently.

Each agent should assume that another agent may be modifying a different
subsystem in a separate Git branch or worktree.

Rules:

1. Only modify files required for the assigned task.
2. Avoid unrelated refactors.
3. Do not reformat unrelated files.
4. Do not change shared contracts unless the assigned task explicitly
   requires it.
5. Prefer adding interfaces over modifying another agent's subsystem.
6. If a required shared contract must change, document the change clearly.
7. Keep commits scoped to one task.
8. Do not merge other agents' branches yourself unless explicitly asked.
9. Run tests relevant to your subsystem before completing work.
10. Ensure your changes can be merged independently.

---

# Documentation Hierarchy

When documentation conflicts, use this priority order:

1. `AGENTS.md`
2. `docs/decisions/*.md`
3. `docs/PRODUCT_SPEC.md`
4. `docs/ARCHITECTURE.md`
5. `docs/REALTIME_PROTOCOL.md`
6. `docs/API.md`
7. `docs/DATABASE.md`
8. `docs/TESTING.md`
9. `docs/BENCHMARKS.md`
10. `AI-First Development Plan_ Real-Time Collaborative Editor.md`
11. `docs/tasks/README.md`
12. `README.md`

Architecture Decision Records take precedence over general architecture documentation because they record explicit technical decisions.

If implementation no longer matches an ADR, do not silently update the implementation. Either:

* follow the ADR, or
* explicitly revise/supersede the ADR and update all affected documentation.

---

# File Responsibilities

## `AGENTS.md`

Purpose:

* permanent instructions for AI-assisted development
* development principles
* documentation rules
* implementation constraints

Update this file only when project-wide development rules change.

Do not use this file for implementation progress or temporary notes.

---

## `AI-First Development Plan_ Real-Time Collaborative Editor.md`

Purpose:

* high-level development strategy
* project phases
* AI-assisted workflow
* recommended implementation order
* overall development philosophy

Before beginning a major phase, review this document.

Update it when:

* major project phases change
* implementation order changes substantially
* development workflow changes

Do not use it as the detailed source of truth for API contracts or architecture.

---

## `README.md`

Purpose:

* public-facing project overview
* concise explanation of what the system does
* technology stack
* major features
* architecture summary
* setup and local development instructions
* testing instructions
* benchmark summary once measured
* deployment information once deployed

Keep this readable for recruiters, engineers, and GitHub visitors.

Do not overload the README with low-level implementation details that belong in `/docs`.

Never publish unverified benchmark numbers.

---

# Core Documentation

## `docs/PRODUCT_SPEC.md`

Purpose:

* define WHAT the system should do

Contains:

* project goals
* non-goals
* user-facing requirements
* functional requirements
* non-functional requirements
* expected system behavior
* feature scope
* performance objectives

Consult this file before implementing new features.

Update it when:

* product requirements change
* features are added or removed
* expected user behavior changes
* performance targets change

Do not place implementation-specific architecture details here unless required to describe a product constraint.

---

## `docs/ARCHITECTURE.md`

Purpose:

* define HOW the overall system is structured

Contains:

* system components
* frontend/backend boundaries
* service responsibilities
* request and data flows
* WebSocket architecture
* Redis role
* PostgreSQL role
* scaling model
* deployment topology
* failure handling
* observability
* architecture diagrams

Consult before making significant structural changes.

Update whenever:

* components are added or removed
* communication patterns change
* infrastructure changes
* service boundaries change
* scaling strategy changes

Use Mermaid diagrams where diagrams improve clarity.

Do not duplicate detailed protocol or database schemas that belong in their dedicated files.

---

## `docs/API.md`

Purpose:

* source of truth for HTTP API contracts

Contains:

* REST endpoints
* HTTP methods
* URLs
* request payloads
* response payloads
* status codes
* authentication requirements
* validation rules
* error response formats

Before modifying controllers or API clients:

* read this file
* preserve documented contracts unless explicitly changing them

If an API contract changes:

1. update `docs/API.md`
2. update backend implementation
3. update frontend consumers
4. update tests

Do not silently introduce undocumented endpoints.

---

## `docs/REALTIME_PROTOCOL.md`

Purpose:

* source of truth for real-time client/server communication

Contains:

* WebSocket lifecycle
* connection behavior
* message/event types
* payload schemas
* edit operations
* acknowledgments
* presence messages
* cursor messages
* synchronization messages
* sequencing/version information
* error events
* reconnection behavior

Before modifying collaborative editing or WebSocket code, read this document.

All WebSocket event types used by the application should be documented here.

If the wire protocol changes:

* update this document
* update producers
* update consumers
* update protocol tests

Do not create ad hoc WebSocket messages that bypass this protocol.

---

## `docs/DATABASE.md`

Purpose:

* source of truth for persisted data

Contains:

* entities
* tables
* columns
* relationships
* indexes
* constraints
* document persistence strategy
* version history representation
* user/document permissions
* migration strategy
* Redis vs PostgreSQL ownership of data

Consult before modifying persistence code.

For schema changes:

1. update the database documentation
2. create or update migrations
3. update persistence models
4. update queries/repositories
5. update tests

Do not store durable canonical data only in Redis unless explicitly documented.

---

## `docs/TESTING.md`

Purpose:

* define testing philosophy and required test coverage

Contains:

* unit testing strategy
* integration testing strategy
* WebSocket testing
* concurrency testing
* database testing
* frontend testing
* end-to-end testing
* CI expectations
* regression-testing requirements

Before implementing a feature, determine which tests should validate it.

Every meaningful bug fix should include a regression test where practical.

Important real-time behavior should not rely only on manual browser testing.

---

## `docs/BENCHMARKS.md`

Purpose:

* define reproducible performance evaluation

Contains:

* benchmark environment
* hardware/environment information
* load generation methodology
* workload definitions
* concurrency levels
* run duration
* warm-up behavior
* measurement methodology
* latency definitions
* throughput definitions
* result tables
* optimization comparisons

Metrics of interest include:

* concurrent WebSocket connections
* simultaneous editors per document
* edit operations per second
* p50 synchronization latency
* p95 synchronization latency
* p99 synchronization latency
* error rate
* dropped messages
* CPU utilization
* memory consumption
* PostgreSQL writes per second
* Redis operations/message throughput
* persistence latency

Never place estimated values in this file as measured results.

Clearly distinguish:

* performance targets
* hypothetical test scenarios
* actual benchmark results

For actual results, record enough information to reproduce the experiment.

---

# Architecture Decision Records

The `/docs/decisions` directory contains Architecture Decision Records.

ADRs describe important technical choices and the reasoning behind them.

Do not casually contradict an accepted ADR.

Each ADR should contain:

* context
* problem
* considered alternatives
* decision
* rationale
* tradeoffs
* consequences
* status

Possible statuses:

* Proposed
* Accepted
* Superseded
* Deprecated

When changing a major architectural decision:

* do not simply rewrite history
* create a new ADR when appropriate
* mark the previous ADR as superseded
* link the two decisions

---

## `docs/decisions/ADR-001-sync-strategy.md`

Purpose:

* collaborative editing synchronization algorithm

Covers topics such as:

* Operational Transformation
* CRDTs
* operation ordering
* conflict resolution
* convergence guarantees
* server/client responsibilities

Read this before modifying synchronization logic.

Correctness is more important than maximizing feature count.

Changes to synchronization semantics require:

* updated ADR
* deterministic concurrency tests
* protocol updates if necessary
* architecture updates if necessary

---

## `docs/decisions/ADR-002-websocket-protocol.md`

Purpose:

* architectural decisions around real-time transport

Covers:

* WebSocket usage
* connection model
* message routing
* acknowledgments
* ordering
* connection recovery
* heartbeat behavior
* protocol-level reliability assumptions

Read this together with `docs/REALTIME_PROTOCOL.md`.

The ADR describes WHY the protocol was designed this way.

`REALTIME_PROTOCOL.md` describes the actual protocol contract.

---

## `docs/decisions/ADR-003-persistence-strategy.md`

Purpose:

* decisions regarding document persistence

Covers:

* when edits become durable
* autosaving
* batching
* snapshots
* operation logs
* document revisions
* crash recovery
* PostgreSQL ownership

Read before changing autosave, revision history, or persistence behavior.

Performance optimizations must not silently weaken durability guarantees.

---

## `docs/decisions/ADR-004-redis-architecture.md`

Purpose:

* define Redis's role in the architecture

Covers:

* Redis Pub/Sub or Streams
* cross-instance event propagation
* ephemeral state
* presence
* caching
* distributed coordination
* failure behavior

Do not treat Redis as the durable source of truth unless explicitly specified.

Before introducing another Redis use case, determine whether it belongs in this ADR.

---

## `docs/decisions/ADR-005-aws-architecture.md`

Purpose:

* define production/deployment infrastructure

Covers:

* AWS services
* network topology
* deployment architecture
* backend hosting
* PostgreSQL hosting
* Redis hosting
* static frontend hosting
* load balancing
* WebSocket compatibility
* scalability
* logging/monitoring

Read before making deployment or infrastructure changes.

Prefer infrastructure appropriate for a portfolio project rather than unnecessary enterprise complexity.

Cost should be considered when selecting AWS resources.

---

# Task Documentation

## `docs/tasks/README.md`

Purpose:

* track implementation work at a task level

Use this for:

* current implementation phase
* completed tasks
* pending tasks
* task dependencies
* verification requirements

Tasks should be concrete and independently verifiable.

Example:

```markdown
### RT-003: Broadcast document operations

Status: In Progress

Requirements:
- Accept document operations through WebSocket
- Validate document membership
- Broadcast operation to connected collaborators
- Exclude sender when appropriate
- Return acknowledgment

Verification:
- Unit tests pass
- Integration test with two WebSocket clients passes
- No persistence required during this task
```

Do not use task files as replacements for architecture documentation.

When implementing a task:

1. understand its requirements
2. inspect affected documentation
3. implement the smallest complete solution
4. add tests
5. run tests
6. update task status

---

# Development Workflow

For every implementation request, follow this workflow.

## Step 1 — Inspect

Before coding:

* read the requested task
* read relevant documentation
* inspect existing implementation
* inspect existing tests

Do not assume the requested functionality is completely absent.

---

## Step 2 — Explain the Intended Change

Before making substantial changes, briefly identify:

* files expected to change
* architectural components involved
* expected behavior
* relevant tests

Keep this concise.

---

## Step 3 — Implement

Implement only the requested scope.

Do not automatically implement future roadmap phases.

Avoid unrelated refactors unless necessary for correctness.

If a refactor is needed, explain why.

---

## Step 4 — Test

Run relevant:

* unit tests
* integration tests
* build checks
* type checks
* linting
* protocol tests

For synchronization or concurrency changes, include deterministic tests whenever possible.

Do not claim a feature works merely because the code compiles.

---

## Step 5 — Document

Update documentation when behavior has changed.

Examples:

REST endpoint change:
→ `docs/API.md`

WebSocket message change:
→ `docs/REALTIME_PROTOCOL.md`

database change:
→ `docs/DATABASE.md`

architecture change:
→ `docs/ARCHITECTURE.md`

testing methodology change:
→ `docs/TESTING.md`

benchmark change:
→ `docs/BENCHMARKS.md`

architectural decision:
→ appropriate ADR

project usage/setup change:
→ `README.md`

task completion:
→ `docs/tasks/README.md`

---

## Step 6 — Report

After implementation, report:

1. What changed
2. Important design decisions
3. Tests that were run
4. Test results
5. Documentation updated
6. Anything intentionally left unimplemented
7. Any risks or follow-up work

Do not claim success if tests failed.

---

# Collaborative Editing Requirements

Treat collaborative editing as a correctness-sensitive distributed system.

Important properties include:

* eventual convergence
* deterministic operation handling
* no silent loss of valid edits
* defined ordering semantics
* duplicate-operation handling where required
* reconnection behavior
* synchronization after temporary disconnection

Do not resolve concurrency by simply allowing "last write wins" for the entire document unless the documented architecture explicitly requires it.

Synchronization algorithms should be separately testable from networking code.

Keep synchronization logic isolated from:

* HTTP controllers
* WebSocket transport
* PostgreSQL repositories
* Redis adapters
* React components

---

# Performance Engineering

Performance work must follow measurement.

Use this sequence:

1. establish baseline
2. reproduce workload
3. measure
4. identify bottleneck
5. make targeted optimization
6. rerun identical benchmark
7. compare results
8. document results

Do not write statements such as:

> improved latency by 40%

unless both before and after values were actually measured under equivalent conditions.

Prefer reporting:

* workload
* environment
* before value
* after value
* percentage difference

---

# Resume Metrics

This project is intended to eventually produce defensible engineering metrics.

Potential resume metrics include:

* supported X concurrent WebSocket clients
* supported X simultaneous editors on one document
* processed X edit operations/sec
* achieved X ms p95 synchronization latency
* reduced PostgreSQL writes by X%
* improved throughput by X%
* reduced synchronization latency by X%
* completed X automated tests

Do not optimize specifically to produce impressive numbers at the expense of correctness.

Do not put unmeasured numbers into README documentation as factual achievements.

Benchmark targets are allowed, but they must be clearly labeled as targets.

---

# Code Quality

Prefer:

* explicit code
* small cohesive components
* descriptive names
* clear interfaces
* dependency injection where useful
* testable business logic
* structured logging
* explicit error handling

Avoid:

* unnecessary abstraction
* speculative extensibility
* giant service classes
* duplicated synchronization logic
* hidden global state
* undocumented magic values
* excessive dependencies

Comments should explain WHY something is necessary, especially around concurrency and distributed-system behavior.

Do not comment obvious syntax.

---

# Git and Change Discipline

Keep changes scoped to the requested task.

Do not:

* delete unrelated code
* rewrite large sections unnecessarily
* rename many files without reason
* modify formatting across unrelated files
* introduce secrets or credentials

Never commit:

* passwords
* API keys
* AWS credentials
* private keys
* database passwords

Use environment variables for secrets.

---

# AI-Assisted Development Principle

The purpose of Codex is to accelerate implementation while preserving developer understanding.

Do not optimize for producing the maximum amount of code.

Optimize for:

1. correctness
2. architecture that can be explained in a technical interview
3. measurable engineering decisions
4. maintainability
5. testability
6. reproducibility
7. useful distributed-systems experience
8. reasonable project scope

When an implementation is technically impressive but unnecessarily complicated, prefer the simpler architecture unless the added complexity demonstrates a specific project goal.

When introducing an important concept, structure the implementation clearly enough that the developer can inspect, understand, test, and explain it.
