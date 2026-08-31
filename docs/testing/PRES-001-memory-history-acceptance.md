# PRES-001 Cursor-History Memory Acceptance Review

This is a test-side acceptance analysis, not a production design decision. The
PRES-001 contract requires enough recent canonical history to validate and map
stale cursors, but does not freeze the history-window size or representation.

## Deterministic stress model

Use a document containing 1,000,000 UTF-16 code units and a recent window of
256 one-code-unit edits. A flat UTF-16 copy requires approximately 2,000,000
bytes before string/object/container overhead. Keeping one full copy for every
revision therefore retains approximately 512,000,000 payload bytes before
overhead:

```text
1,000,000 code units * 2 bytes * 256 revisions = 512,000,000 bytes
```

That representation is blocking for PRES-001 even though the revision window
is numerically bounded. Increasing the document toward its permitted maximum
must not multiply full-document storage by the retained cursor-history count.

## Acceptance classification

- **Acceptable:** one room materialization plus bounded canonical operation
  deltas/checkpoints; cursor state proportional to active connections; stale
  lookup proportional to the bounded recent operation window; old cursors are
  dropped when the needed delta is unavailable.
- **Concerning:** multiple full-text checkpoints retained in heap without a
  measured byte budget, or a history bound expressed only as a revision count
  despite document-size-dependent entries.
- **Blocking:** one large full-text copy per recent revision, unbounded cursor
  or revision retention, PostgreSQL reconstruction for each cursor frame, or
  any memory strategy where high-rate awareness traffic grows durable state.

## Integration review gate

After the independent production branch is integrated, inspect only the
observable room-history diagnostics or a heap histogram from this workload:

1. initialize one room with a 1,000,000-code-unit document;
2. apply 256 single-code-unit canonical edits;
3. send stale cursors spanning the retained and expired boundaries;
4. verify recent cursors map and expired cursors drop;
5. verify retained full-document-sized objects remain constant rather than
   growing with the revision window.

If the implementation exposes no stable diagnostic seam, the deterministic
structural test is whether its retained history elements contain operations
and revision metadata rather than complete materialized text. Exact JVM or
browser heap byte assertions are intentionally excluded because string
compression, ropes, garbage collection, and object layout make them flaky.

Baseline classification: **not yet classifiable** because canonical master has
no PRES-001 cursor-history implementation. The acceptance target above makes a
per-revision full-copy implementation blocking at integration review.
