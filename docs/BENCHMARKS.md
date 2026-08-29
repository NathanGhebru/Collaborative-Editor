# Benchmark Methodology — Real-Time Collaborative Editor

## Status

**Status:** Accepted
**Benchmark owner:** Codex
**Browser verification:** Antigravity

This document defines how performance measurements for the Real-Time Collaborative Editor are generated, stored, compared, and eventually used in the README or resume.

No performance number is considered achieved until it has reproducible benchmark evidence.

## Current measured-results status

**Measured results: none.**

| Benchmark | Environment | Commit | Result |
| --- | --- | --- | --- |
| No official runs recorded | — | — | — |

All numeric values elsewhere in this document are targets, planned workload parameters, comparison thresholds, formulas, or schema examples unless they appear in this measured-results section with linked raw artifacts.

---

# 1. Purpose

The project has performance targets including:

```text
50+ simultaneous editors/document
500+ concurrent WebSocket connections
1,000+ document operations/sec
<100 ms p95 synchronization latency
10,000+ document revisions
```

Planned optimization targets include:

```text
~40% synchronization-latency improvement
~60% reduction in database write transactions
```

These numbers begin as:

```text
TARGETS
```

and become:

```text
MEASURED RESULTS
```

only after benchmark evidence exists.

---

# 2. Benchmark Principle

The optimization process is:

```text
baseline
   ↓
profile
   ↓
identify bottleneck
   ↓
change one variable
   ↓
rerun identical benchmark
   ↓
compare
```

Do not optimize based only on intuition.

---

# 3. Results Directory

Store benchmark artifacts under:

```text
benchmarks/
├── README.md
├── baseline/
│
└── historical/
    ├── <task-or-milestone>/
    └── ...
```

Each run should contain machine-readable output.

---

# 4. Recommended Run Structure

Example:

```text
benchmarks/historical/<task-or-milestone>/
└── <run-timestamp>/
    ├── metadata.json
    ├── results.json
    ├── client-summary.json
    ├── server-metrics.json
    ├── database-metrics.json
    └── notes.md
```

---

# 5. Git Commit Requirement

Every official benchmark records:

```text
Git commit SHA
```

Example:

```json
{
  "commit": "a46e732..."
}
```

A performance result without corresponding code revision is incomplete.

---

# 6. Environment Metadata

Every official run records:

```text
date/time
commit SHA
branch
environment
OS
CPU
CPU cores
RAM
Java version
Node version
PostgreSQL version
Redis version
backend instance count
backend CPU/memory limits
database configuration
Redis configuration
load generator
network topology
```

AWS tests additionally record:

```text
AWS region
ECS task configuration
RDS instance configuration
ElastiCache configuration
load generator region/location
```

---

# 7. Benchmark Types

The project uses several benchmark categories:

```text
microbenchmark
single-server collaboration
multi-instance collaboration
connection scale
single-document contention
multi-document throughput
reconnect
persistence
long-history recovery
AWS production/staging
```

---

# 8. Microbenchmarks

Microbenchmarks isolate algorithms such as:

```text
OT transform
operation application
operation serialization
snapshot generation
```

Java microbenchmarks should use:

```text
JMH
```

where appropriate.

Microbenchmarks do not replace end-to-end testing.

---

# 9. OT Microbenchmark

Measure transformation cost for history sizes such as:

```text
0
10
100
1,000
5,000
```

operations.

Record:

```text
operations transformed/sec
mean latency
p50
p95
p99
allocations where measurable
```

---

# 10. Document Size Matrix

Where applicable benchmark documents of:

```text
1 KB
10 KB
100 KB
1 MB
```

The project does not need to optimize arbitrary huge documents.

---

# 11. Standard Collaboration Benchmark

The primary benchmark simulates realistic concurrent editors.

Baseline scenario:

```text
50 editors
1 shared document
active typing
fixed operation rate
defined text distribution
defined test duration
```

---

# 12. Operation Distribution

The workload should resemble editing rather than only appending text.

Example standard mix:

```text
70% INSERT
30% DELETE
```

Possible secondary workload:

```text
80% INSERT
20% DELETE
```

The exact official mix must remain fixed between comparisons.

The percentages above are planned workload parameters, not observations. An official run must store the concrete operation mix, random seed, position distribution, insert/delete length distributions, client rate, and document seed in its workload manifest.

---

# 13. Insert Length

Recommended distribution:

```text
most inserts: 1–5 UTF-16 code units
some inserts: full words
small percentage: pasted sentences
```

A benchmark containing only one-character inserts should be labeled accordingly.

---

# 14. Delete Length

Recommended distribution:

```text
mostly 1–5 code units
occasionally larger selections
```

---

# 15. Edit Position

Operations should not always occur at document end.

Choose positions across the document.

Possible distribution:

```text
recent cursor locality
+
occasional random repositioning
```

This creates realistic transformation conflicts.

---

# 16. Standard Test Duration

Performance runs should include:

```text
warm-up
+
measurement interval
```

Example:

```text
30-second warm-up
120-second measurement
```

These are planned defaults. Every result records the actual warm-up and measurement durations.

Longer runs should be used to detect stability problems.

---

# 17. Throughput Definition

Document operation throughput is:

> Number of canonical logical document operations durably accepted by the backend per second during the measurement interval.

Formula:

```text
accepted operations
-------------------
measurement seconds
```

Do not count:

* rejected operations,
* duplicate retries,
* cursor messages,
* presence messages.

---

# 18. 1K Operations/Sec Target

The primary throughput target is:

```text
>= 1,000 accepted document operations/sec
```

under an explicitly recorded workload.

Success additionally requires:

```text
acceptable error rate
correct final convergence
durable persistence
```

A system that drops operations to increase throughput fails.

---

# 19. Synchronization Latency Definition

Primary user-visible synchronization latency is:

> Time from source-client operation submission until a remote client has applied the corresponding accepted operation.

Conceptually:

```text
source submit timestamp
        ↓
backend
        ↓
remote apply timestamp
```

For the standard collaboration benchmark, each successfully applied source-operation/remote-client pair is one latency sample. All non-origin active clients expected to receive the operation are included. A missing expected application is an error, not a latency sample that can be silently omitted. Secondary summaries may additionally report the slowest recipient per operation.

---

# 20. Latency Timestamping

Because client machines may have clock skew, official latency measurements should preferably be generated by:

```text
one load generator controlling multiple logical clients
```

so timestamps share one monotonic clock.

For distributed load generators, clock methodology must be documented.

---

# 21. Latency Samples

For each operation, record where practical:

```text
operation ID
source send time
server receive time
server commit time
server publish time
remote receive time
remote apply time
```

This permits stage-level diagnosis.

---

# 22. Latency Percentiles

Report:

```text
p50
p95
p99
maximum
```

Primary target:

```text
p95 < 100 ms
```

Do not report only averages.

---

# 23. Server Processing Latency

Separately measure:

```text
operation received
↓
PostgreSQL commit
```

This isolates backend processing from network/client rendering.

---

# 24. Redis Propagation Latency

Measure where possible:

```text
leader publish
↓
receiving backend event handler
```

This helps identify distributed messaging bottlenecks.

---

# 25. Browser Apply Latency

Antigravity may measure:

```text
WebSocket message received
↓
editor update rendered
```

to distinguish server latency from frontend rendering latency.

---

# 26. 50 Editors/Document Target

Test:

```text
>= 50 active editors
```

on the same document.

Each editor should generate actual operations.

Do not count 50 idle sockets as proof of 50-editor collaboration.

---

# 27. 500 WebSocket Connection Target

Connection benchmark must establish:

```text
>= 500 simultaneous WebSocket connections
```

Connections should:

* authenticate,
* join documents,
* maintain heartbeat,
* receive protocol messages.

A secondary test should have meaningful traffic on a subset/all connections.

---

# 28. Connection Ramp

Connections should ramp gradually.

Example:

```text
0 → 500 over 60 seconds
```

Also test rapid reconnect bursts separately.

This avoids confusing startup spikes with steady-state limits.

---

# 29. Connection Metrics

Record:

```text
successful connections
failed connections
connection setup latency
active connections
unexpected disconnects
reconnects
memory/connection
CPU
network throughput
```

---

# 30. Multi-Document Benchmark

Not all production users edit the same document.

Secondary scenario:

```text
500 clients
10–50 documents
clients distributed across documents
```

Measure:

```text
throughput
latency
leader count
CPU
memory
Redis traffic
DB traffic
```

---

# 31. Multi-Instance Benchmark

A valid horizontal-scaling benchmark uses at least:

```text
2 Spring Boot instances
```

Prefer:

```text
3
```

for scaling comparisons.

Clients should be distributed across instances.

---

# 32. Scale-Out Comparison

Example comparison:

```text
1 backend instance
2 backend instances
3 backend instances
```

with identical client workload.

Measure:

```text
throughput
p95 latency
CPU
memory
Redis traffic
database load
```

---

# 33. Horizontal Scaling Claim

Do not claim:

```text
horizontally scalable
```

solely because multiple containers start.

Prove that:

```text
clients on different backend instances
edit same document successfully
```

and demonstrate scale behavior through benchmark evidence.

---

# 34. Leader Failure Benchmark

During active workload:

1. identify leader,
2. terminate leader,
3. measure time until new leader accepts operations.

Record:

```text
failover duration
client errors
retries
operations duplicated
operations lost
final convergence
```

Expected correctness:

```text
0 committed operations lost
0 duplicate document edits
```

---

# 35. Reconnect Benchmark

Simulate a percentage of clients disconnecting.

Example:

```text
10%
25%
50%
```

Then reconnect.

Measure:

```text
reconnect completion time
resynchronization latency
retry count
CPU spike
Redis load
database recovery reads
```

---

# 36. Reconnect Storm

Separate stress case:

```text
500 clients disconnect
↓
all attempt reconnect
```

Jitter should prevent a perfectly synchronized reconnect burst.

Measure recovery behavior.

---

# 37. Persistence Benchmark

Compare:

```text
naive per-operation persistence
```

against:

```text
batched persistence
```

under identical workload.

---

# 38. Database Write Reduction Definition

Primary metric:

```text
1 -
(
optimized write transactions
/
baseline write transactions
)
```

Example:

```text
baseline = 10,000 transactions
optimized = 4,000 transactions

reduction =
1 - 4000 / 10000
= 60%
```

Only measured values should be reported.

---

# 39. DB Write Benchmark Metrics

Record:

```text
write transactions
operations persisted
average batch size
p95 batch size
commit latency
DB CPU
DB connections
operations/sec
sync p95 latency
```

---

# 40. Batching Safety

An optimization is rejected if it lowers database writes but causes unacceptable:

```text
p95 synchronization latency
data-loss behavior
reconnect problems
```

---

# 41. Latency Optimization Claim

The planned target:

```text
~40% latency reduction
```

requires:

```text
before
+
after
```

measurements using the identical benchmark.

Formula:

```text
(baseline p95 - optimized p95)
--------------------------------
baseline p95
```

---

# 42. Optimization Acceptance Rule

For general performance optimization, a useful default is:

Keep an optimization if:

```text
throughput improves >= 10%
```

or:

```text
p95 decreases >= 10%
```

while the other primary metric regresses by no more than:

```text
5%
```

and correctness remains unchanged.

This threshold may be overridden by a task with justification.

---

# 43. Baseline Discipline

Before optimizing a subsystem:

```text
record baseline first
```

Never reconstruct an imagined baseline afterward.

---

# 44. One-Variable Principle

Performance experiments should ideally change one primary variable.

Bad:

```text
change batching
change Redis serialization
change thread pool
change DB indexes
then benchmark
```

because attribution becomes unclear.

Prefer:

```text
change batching only
↓
benchmark
```

then continue.

---

# 45. JVM Metrics

Record:

```text
CPU
heap used
heap maximum
GC count
GC pause time
thread count
```

where relevant.

---

# 46. Backend Memory

Track memory as connections and active documents increase.

Particularly record:

```text
memory/client
memory/active document
recent OT history size
outbound WebSocket queues
```

---

# 47. PostgreSQL Metrics

Record where available:

```text
CPU
connections
transaction rate
query latency
write latency
storage
buffer/cache behavior
```

---

# 48. Redis Metrics

Record:

```text
command latency
publish rate
received events
memory
connections
leader lease operations
event throughput
```

---

# 49. Error Rate

Error rate:

```text
failed logical operations
-------------------------
submitted logical operations
```

Report separately:

```text
expected rate-limit rejections
unexpected failures
```

A benchmark should normally target effectively zero unexpected operation failures.

---

# 50. Correctness Verification During Load

Every benchmark must verify at least:

```text
canonical server revision
expected accepted operation count
client convergence
no duplicate operation IDs
no revision gaps
```

Selected benchmarks should compute final content checksums across clients.

---

# 51. Final Checksum

At test completion:

```text
server canonical content hash
```

must equal:

```text
client A hash
client B hash
...
```

for clients that have fully synchronized.

---

# 52. Long-Revision Benchmark

Generate:

```text
10,000+
```

logical revisions.

Measure:

```text
storage growth
snapshot count
recovery time
database size
operation-batch count
```

---

# 53. Recovery Benchmark

After long history:

1. stop leader/backend,
2. start fresh process,
3. reconstruct from PostgreSQL,
4. measure room-ready time.

Record:

```text
snapshot revision
operations replayed
recovery duration
```

---

# 54. Snapshot Benchmark

Compare snapshot intervals such as:

```text
100
500
1,000
5,000 revisions
```

Evaluate:

```text
write overhead
storage
recovery time
```

Select interval based on evidence.

---

# 55. Browser Performance

Antigravity should measure browser behavior for:

```text
1 collaborator
10 collaborators
50 collaborators
```

where practical.

Track:

```text
editor responsiveness
rendering latency
cursor render cost
CPU
memory
long tasks
```

---

# 56. Cursor Traffic Benchmark

Measure cursor rate effects separately.

Compare:

```text
5 updates/sec
10 updates/sec
20 updates/sec
```

to determine whether 20/sec is justified.

---

# 57. CI Performance Regression Suite

Normal CI should not attempt full-scale benchmarking.

Use a short deterministic scenario to detect large regressions.

Example:

```text
20–50 clients
30 seconds
fixed operation rate
```

The test should have generous thresholds to avoid false failures from noisy runners.

---

# 58. Official Benchmark Environment

Final resume metrics should preferably come from:

```text
controlled AWS staging environment
```

or a well-documented stable local environment.

Do not mix numbers from unrelated environments into one claim.

---

# 59. AWS Benchmark

Record:

```text
ECS task count
task CPU
task RAM
RDS type
ElastiCache type
AWS region
load-generator location
```

This context should accompany results.

---

# 60. Warm-Up

JVM benchmarks must include warm-up.

Without warm-up, JIT/class-loading behavior may distort measurements.

---

# 61. Repeated Runs

Important benchmarks should run multiple times.

Recommended:

```text
3–5 repetitions
```

Report:

* median run,
* range,
* whether significant variance exists.

Do not choose only the best result.

---

# 62. Outlier Handling

Do not silently delete bad runs.

If excluding a run:

```text
state why
```

Examples:

```text
AWS deployment occurred during test
load generator failed
database restarted
```

---

# 63. Benchmark Result JSON

Suggested structure with deliberately empty result fields:

```json
{
  "benchmark": "standard-collaboration",
  "commit": "<git-sha>",
  "timestamp": "<ISO-8601 timestamp>",
  "environment": {
    "backendInstances": null,
    "clients": null,
    "editorsPerDocument": null
  },
  "results": {
    "acceptedOperationsPerSecond": null,
    "syncLatencyMs": {
      "p50": null,
      "p95": null,
      "p99": null
    },
    "errorRate": null
  }
}
```

`null` fields must be replaced by actual captured values in a completed run; this example contains no project results.

---

# 64. Benchmark Notes

Every important benchmark should include a short human-readable notes file explaining:

```text
what changed
what was expected
what happened
possible bottleneck
next experiment
```

---

# 65. Profiling

When a target is missed, profile before changing code.

Possible tools:

```text
Java Flight Recorder
async-profiler
Spring/Micrometer metrics
PostgreSQL EXPLAIN ANALYZE
browser performance tools
```

---

# 66. Bottleneck Categories

Potential bottlenecks:

```text
OT transform
Java allocation/GC
WebSocket serialization
WebSocket outbound queues
Redis propagation
database commits
snapshot generation
frontend rendering
load generator itself
```

The load generator must be monitored so it does not become the limiting factor.

---

# 67. Load Generator Validation

Track load-generator:

```text
CPU
memory
network
event-loop delay
```

If the load generator saturates first, server metrics are invalid.

---

# 68. Resume Claim Rules

A resume claim may be made only when:

```text
benchmark exists
+
commit recorded
+
environment recorded
+
result reproducible
+
correctness verified
```

---

# 69. Acceptable Claim Example

After measurement:

```text
Sustained 1,120 document operations/sec
across 500 concurrent WebSocket clients
with 84 ms p95 synchronization latency.
```

Only if those numbers were actually measured.

---

# 70. Unacceptable Claim

Do not state:

```text
Supports 1K+ ops/sec
```

merely because it was an original design target.

---

# 71. Optimization Claim Evidence

For a statement such as:

```text
reduced synchronization latency by 40%
```

retain:

```text
baseline JSON
optimized JSON
code commits
comparison notes
```

---

# 72. Benchmark README Summary

`benchmarks/README.md` should eventually contain a concise table:

```text
Benchmark | Commit | Clients | Throughput | p95 | Result
```

with links/paths to underlying artifacts.

---

# 73. Benchmark Ownership

## Codex

Owns:

```text
backend benchmark harness
load generation
server profiling
PostgreSQL measurements
Redis measurements
JVM analysis
comparison reports
```

## Antigravity

Owns/independently verifies:

```text
browser realism
frontend responsiveness
multi-browser behavior
browser-observed latency
UX regression
```

---

# 74. Benchmark Acceptance

A benchmark result is valid only if:

* workload completed,
* no unexplained failures occurred,
* canonical operation counts match,
* final state converged,
* load generator was not saturated,
* environment metadata exists,
* raw results were saved.

---

# 75. Final Benchmark Targets

The project aims to establish evidence for:

```text
50+ active simultaneous editors/document
500+ concurrent WebSocket connections
1,000+ accepted document operations/sec
<100 ms p95 synchronization latency
10,000+ logical document revisions
```

Potential optimization evidence:

```text
~40% lower p95 synchronization latency
~60% fewer DB write transactions
```

The measured final numbers—not these planned targets—determine the finished project description.

---

# 76. Benchmark Principle

The project uses the rule:

> **If a performance improvement cannot be reproduced, it is not a project metric.**

And:

> **Correctness is part of every performance benchmark.**
