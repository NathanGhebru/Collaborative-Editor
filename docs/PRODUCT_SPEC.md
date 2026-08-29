# Product Specification — Real-Time Collaborative Editor

## Purpose and status

**Status:** Current product scope

This document defines what the product must do and what users should observe. It does not define wire messages, database tables, service topology, or deployment resources; those contracts belong in the architecture and dedicated technical documents.

## 1. Product overview

The Real-Time Collaborative Editor is a web application in which authenticated users create, share, and edit text documents together. Changes from one collaborator become visible to others in near real time, concurrent changes converge deterministically, and saved work survives application restarts.

The distinguishing feature is collaboration correctness. The initial product favors a rigorously testable plain-text editing model over broad rich-text functionality.

## 2. Product objectives

An authenticated user must be able to:

1. create and access an account;
2. create, list, open, rename, and delete documents;
3. share a document with another registered user;
4. edit the same document concurrently from multiple browsers;
5. see remote edits, presence, cursors, and selections;
6. understand whether the editor is connected, reconnecting, saving, saved, or in error;
7. recover after a temporary disconnect without silently overwriting newer work;
8. inspect prior document versions and, when authorized, restore one;
9. reopen persisted content after clients and application servers restart.

The same collaboration workflow must remain correct when participants are served by different backend instances.

## 3. Target users and use cases

Primary users are individuals and small groups collaborating on shared text, such as students editing notes or small teams drafting a document.

The application is a distributed-systems portfolio project, not an attempt to reproduce every feature of a commercial office suite.

## 4. Primary user journey

```text
User A signs in and creates a document
        ↓
User A grants edit access to User B
        ↓
both users open the document
        ↓
both appear in the presence UI
        ↓
both edit, including concurrently
        ↓
both clients converge to the same text
        ↓
save state reaches Saved
        ↓
one user disconnects and later reconnects
        ↓
missed changes and pending valid edits are reconciled
        ↓
both clients converge again
```

## 5. Accounts and authentication

The product must support registration, login, logout, and continuation of a valid authenticated session.

Protected behavior must reject unauthenticated access. Authentication failures must be visible and must not reveal whether a particular account exists.

Each user has a stable identity and a user-visible display name. Exact account fields and authentication mechanics are technical contracts outside this document.

## 6. Document management

### 6.1 Create and list

An authenticated user can create a document with a title and optional initial text. The creator becomes its owner.

The document list shows only documents the user can access and provides enough information to identify and open each document.

### 6.2 Open and rename

An owner or editor can open and rename a shared document. Opening returns one coherent document state; unauthorized users receive no content.

### 6.3 Delete

Only the owner can delete a document in v1. Deletion removes the document from all users' accessible lists and makes its content and history unavailable through the product.

## 7. Sharing and authorization

The initial roles are:

| Role | Capabilities |
| --- | --- |
| Owner | Read, edit, rename, share, revoke access, delete, create versions, and restore versions |
| Editor | Read, edit, rename, and create versions |
| No access | No document, collaboration, sharing, or history access |

Authorization must be enforced by the server for both request/response and live collaboration behavior. Hiding a control in the browser is not an authorization boundary.

If edit access is revoked while a user is connected, that user's live editing session must end promptly.

## 8. Editor scope

Protocol v1 edits a linear Unicode text document and must support:

- insertion and deletion;
- multiline text;
- cursor placement and selections;
- keyboard navigation;
- document-title editing;
- responsive local editing while an operation awaits confirmation.

Collaborative rich-text structure and formatting are deferred. Formatting must not be advertised as collaborative until it is represented by the synchronization model and covered by convergence tests.

## 9. Real-time collaboration

Multiple authorized users connected to one document can edit simultaneously.

The product must ensure that:

- valid edits are not silently lost;
- accepted edits reach active collaborators;
- concurrent edits follow deterministic semantics;
- duplicates do not apply an edit twice;
- all active clients eventually converge when communication succeeds;
- stale clients do not overwrite a newer whole-document state;
- invalid operations fail predictably without corrupting the document.

The synchronization algorithm and exact operation semantics are selected in [decisions/ADR-001-sync-strategy.md](decisions/ADR-001-sync-strategy.md). The browser/server event contract is defined in [REALTIME_PROTOCOL.md](REALTIME_PROTOCOL.md).

## 10. Presence, cursors, and selections

An active collaboration session shows who is present. Remote cursors and selections identify their collaborator and update responsively without blocking document edits.

Presence and cursor state may be briefly stale during failure or reconnect. They do not need historical persistence and their loss must never affect document content correctness.

Multiple connections from one user must not appear as duplicate people unless the UI intentionally exposes connection count.

## 11. Connection and reconnect behavior

The UI distinguishes at least:

```text
Connecting
Connected
Reconnecting
Resynchronizing
Disconnected
Save error
```

A temporary disconnection must not silently discard unacknowledged local work. On reconnect, the client recovers missed accepted changes, detects already accepted retries, reconciles remaining valid local intent, and returns to a converged state.

If automatic reconciliation is unsafe, the product must preserve the user's text for explicit recovery and must not imply that it was saved. The exact behavior for a timeline-changing version restore remains an implementation-gate decision in the real-time protocol.

## 12. Autosave and durability feedback

Normal edits are saved automatically; there is no required manual save action.

The editor exposes states such as:

```text
Saving…
Saved
Offline / Reconnecting
Save error
```

`Saved` means all relevant local edits have reached the documented durability boundary. A local edit or receipt by a server alone is not enough to display `Saved`.

Saved documents must survive loss of all application-process memory and normal backend restarts.

## 13. Version history

Authorized users can list and inspect retained historical checkpoints. Owners can restore a checkpoint without deleting the history that followed it.

A restore is consequential and must protect against restoring over changes made after the history view was opened. Connected collaborators must be told to resynchronize to the restored timeline.

User-visible versions are checkpoints, not a promise that every keystroke appears as a separate history entry.

## 14. Error behavior

The product must fail visibly and predictably for:

- invalid or expired authentication;
- insufficient document permission;
- missing documents or versions;
- invalid request or collaboration messages;
- lost live connections;
- stale synchronization state;
- failed durable saving;
- required service unavailability;
- unexpected internal errors.

Recoverable failures should guide the user toward retry or resynchronization. Internal details, document data from unrelated users, credentials, and stack traces must not be exposed.

## 15. Security and privacy requirements

- Passwords are never stored or logged in plaintext.
- Secrets and reusable credentials are never committed to the repository or included in browser bundles.
- Every document read, write, collaboration connection, sharing action, and history action is authorized server-side.
- Production user traffic uses encrypted transport.
- Normal production logs do not contain credentials or complete document bodies.

## 16. Accessibility and usability

Core account, document, and editor workflows must be operable with normal keyboard interaction. Connection, save, and error state must not depend solely on subtle color changes.

Accessibility is part of feature acceptance rather than a final cosmetic phase.

## 17. Performance objectives

These are targets until reproduced by the benchmark methodology:

| Metric | Target |
| --- | ---: |
| Active simultaneous editors on one document | 50+ |
| Concurrent live connections | 500+ |
| Durably accepted document operations | 1,000+ ops/sec |
| Source-submit to remote-apply synchronization latency | <100 ms p95 |
| Logical revisions exercised in storage/recovery testing | 10,000+ |

Optimization objectives are approximately 40% lower p95 synchronization latency after profiling and 60% fewer database write transactions than a one-transaction-per-operation baseline. They are experimental goals, not promised outcomes.

The workload, timing definitions, environment metadata, correctness gates, and result rules are defined in [BENCHMARKS.md](BENCHMARKS.md).

## 18. Reliability and observability requirements

The system must expose enough telemetry to diagnose connection lifecycle, operation acceptance, synchronization latency, persistence latency, retries, failures, and resource use.

Performance, failure recovery, and horizontal behavior must be verifiable by automated tests rather than manual observation alone.

## 19. Non-goals for v1

- Collaborative rich-text trees or extensive formatting
- Spreadsheet or slide editing
- Anonymous public editing
- Offline-first editing across long disconnected periods
- Comments, suggestions, or review workflows
- Enterprise organization administration
- Full-text search across documents
- Native mobile applications
- Audio/video collaboration
- AI writing assistance
- Arbitrary large-file storage

## 20. Product success criteria

The product is functionally successful when automated API, protocol, persistence, and browser tests demonstrate that authorized users can create and share a document, edit it concurrently, observe presence and cursors, recover from disconnection, converge, reload durable content, and use version history—including when collaborators are connected to different backend instances.

Performance targets become achievements only after reproducible benchmark artifacts show both the measured result and preserved correctness.
