# Collaborative Editor Project Rules

This repository's canonical AI development instructions are defined in:

@AGENTS.md

Before making significant changes, follow all applicable instructions in `AGENTS.md`.

The project's technical documentation is:

* @docs/PRODUCT_SPEC.md
* @docs/ARCHITECTURE.md
* @docs/API.md
* @docs/REALTIME_PROTOCOL.md
* @docs/DATABASE.md
* @docs/TESTING.md
* @docs/BENCHMARKS.md
* @docs/tasks/README.md

Architecture decisions are defined in:

* @docs/decisions/ADR-001-sync-strategy.md
* @docs/decisions/ADR-002-websocket-protocol.md
* @docs/decisions/ADR-003-persistence-strategy.md
* @docs/decisions/ADR-004-redis-architecture.md
* @docs/decisions/ADR-005-aws-architecture.md

The high-level development plan is:

@AI-First Development Plan_ Real-Time Collaborative Editor.md

## Required Behavior

Before implementing a task:

1. Read `AGENTS.md`.
2. Read the relevant project documentation.
3. Read the applicable ADRs.
4. Inspect the existing implementation and tests.
5. Follow `docs/tasks/README.md` for implementation sequencing unless explicitly instructed otherwise.

Do not silently contradict an accepted ADR.

Do not invent benchmark results or performance claims.

Do not automatically implement later roadmap phases beyond the requested task.

After making changes:

1. Run relevant tests and verification commands.
2. Report what passed and failed.
3. Update affected documentation.
4. Update task status where applicable.
5. Clearly identify anything intentionally left unimplemented.

The central technical goal of this project is correct, measurable real-time collaborative editing and distributed-system behavior rather than maximizing feature count.
