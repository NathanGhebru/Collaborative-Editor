# Native WebSocket Collaboration Protocol

**Status:** Accepted
**Date:** 2026-08-28
**Decision owner:** Codex
**Reviewer:** Antigravity
**Scope:** Real-time transport choice, authentication approach, lifecycle, reliability, and protocol-level tradeoffs. Exact public messages belong in `docs/REALTIME_PROTOCOL.md`.

---

## 1. Context

The application requires bidirectional communication for:

* document operations,
* operation acknowledgements,
* live remote edits,
* presence,
* cursors,
* selections,
* reconnect synchronization,
* server reset events.

Polling would add unnecessary latency and repeated HTTP overhead.

The application therefore requires a persistent real-time transport.

The major choices considered were:

```text
native WebSockets
STOMP over WebSocket
Server-Sent Events + HTTP writes
HTTP polling
third-party hosted real-time service
```

## 1.1 Problem

The application needs a low-latency, bidirectional transport whose authentication, ordering, retry, backpressure, and recovery behavior can carry the OT protocol consistently across browsers and horizontally scaled backend instances.

## 1.2 Alternatives Considered

The decision compares native WebSockets with STOMP over WebSocket, Server-Sent Events plus HTTP writes, polling, and a hosted real-time service. Sections 4–7 record why each rejected alternative does not fit the required bidirectional lifecycle, explicit OT protocol, or project goals.

---

# 2. Decision

The project will use:

> **Native WebSockets with a project-defined, versioned application protocol.**

Protocol v1 uses:

```text
JSON text frames
```

over:

```text
WebSocket
```

The canonical message specification is:

```text
docs/REALTIME_PROTOCOL.md
```

---

# 3. Why Native WebSockets

Native WebSockets provide:

* full-duplex communication,
* low message overhead,
* direct control over protocol semantics,
* straightforward Spring WebSocket support,
* browser-native client support,
* explicit connection lifecycle,
* direct visibility during performance testing.

The project deliberately benefits from implementing the real-time protocol rather than hiding it behind a higher-level messaging abstraction.

---

# 4. Why Not STOMP

STOMP would provide useful messaging conventions.

However it would also introduce:

* additional framing,
* subscriptions/destination abstractions that do not map perfectly to the OT model,
* additional protocol behavior,
* less direct control over compact collaboration messages.

The project already has:

```text
document rooms
canonical revisions
custom acknowledgements
Redis propagation
OT semantics
```

so a bespoke protocol remains manageable and easier to benchmark.

STOMP is rejected for protocol v1.

---

# 5. Why Not Server-Sent Events

SSE provides server-to-client streaming but not native bidirectional messaging.

Client edits would still require separate HTTP requests.

That would complicate:

* ordering,
* acknowledgements,
* reconnect,
* connection state.

Rejected.

---

# 6. Why Not Polling

Polling creates:

* higher synchronization latency,
* unnecessary requests when idle,
* poor cursor/presence responsiveness,
* inefficient use of server resources.

Rejected.

---

# 7. Why Not Hosted Real-Time Infrastructure

A third-party hosted WebSocket service could simplify scaling.

It would also remove a major engineering feature of the project:

```text
horizontally scaled Spring WebSocket servers
+
Redis Pub/Sub
```

Rejected for the initial project.

---

# 8. Public WebSocket Endpoint

Document collaboration uses:

```text
/ws/v1/documents/{documentId}
```

Production:

```text
wss://
```

Local development may use:

```text
ws://
```

---

# 9. Authentication Decision

Long-lived access credentials will not be placed directly in the WebSocket URL.

Instead:

```text
authenticated REST request
        ↓
short-lived real-time ticket
        ↓
WebSocket connection
        ↓
single-use ticket consumed
```

Ticket endpoint:

```text
POST /api/v1/documents/{documentId}/realtime-ticket
```

---

# 10. Ticket Properties

Tickets are:

```text
single-use
document-scoped
user-scoped
permission-scoped
short-lived
```

Initial lifetime target:

```text
30–60 seconds
```

Tickets are stored temporarily in Redis.

---

# 11. Reason for Ticket Authentication

Browser WebSocket APIs provide less flexibility than normal HTTP APIs for arbitrary authentication headers.

A single-use ticket avoids:

* placing a long-lived access token in URLs,
* persisting reusable credentials in server logs,
* custom cookie dependence for the WebSocket handshake.

---

# 12. Message Envelope

Protocol v1 messages use:

```json
{
  "protocolVersion": 1,
  "type": "client.operation",
  "messageId": "uuid",
  "documentId": "uuid",
  "payload": {}
}
```

Additional fields appear when relevant.

---

# 13. Protocol Version

Every application message identifies:

```text
protocolVersion = 1
```

Breaking changes require:

```text
protocolVersion = 2
```

unless compatibility can be preserved.

---

# 14. Message ID

Every application-level message has:

```text
messageId
```

This provides:

* log correlation,
* diagnostics,
* duplicate-event analysis.

`messageId` is distinct from:

```text
clientOperationId
```

because retransmitting an operation may create a new transport message while preserving the original logical operation identity.

---

# 15. Logical Operation Identity

Retransmission example:

```text
message 1:
messageId = M1
clientOperationId = O1

retry:
messageId = M2
clientOperationId = O1
```

Within the document and synchronization epoch, the backend deduplicates based on:

```text
(documentId, syncEpoch, clientId, O1)
```

not the transport message ID.

---

# 16. Initial Connection Handshake

Normal sequence:

```text
WebSocket handshake accepted
        ↓
client.hello
        ↓
server validates known epoch/revision
        ↓
catch-up operations OR resync request
        ↓
presence snapshot
        ↓
server.ready
```

Edit transmission before `server.ready` is invalid.

---

# 17. Connection State Machine

Frontend state:

```text
DISCONNECTED
     ↓
CONNECTING
     ↓
SYNCHRONIZING
     ↓
CONNECTED
     ↓
RECONNECTING
```

Additional terminal/degraded states:

```text
FORBIDDEN
PROTOCOL_ERROR
SAVE_ERROR
```

The UI must expose meaningful synchronization state to the user.

---

# 18. Operation Messages

Document edits use:

```text
client.operation
server.operation_ack
server.operations
server.operation_rejected
```

The origin client receives both:

* acknowledgement,
* canonical accepted operation.

The canonical operation is delivered before its compact acknowledgement on that socket. The client must treat canonical revision information as authoritative and must tolerate duplicate delivery by operation identity and revision.

---

# 19. Presence Messages

Presence uses:

```text
presence.snapshot
presence.changed
```

Presence is user-visible but not document-critical.

A temporarily dropped presence event must not affect document correctness.

---

# 20. Cursor Messages

Cursor/selection state uses:

```text
cursor.update
cursor.remote
```

Cursor traffic is:

```text
best effort
ephemeral
rate limited
```

Cursor updates may be dropped under pressure.

Document edits may not.

---

# 21. Cursor Frequency

Initial client throttle:

```text
20 updates/sec
```

The limit may change after browser and load testing.

---

# 22. Heartbeats

The system should primarily use standard WebSocket ping/pong where infrastructure permits.

Application heartbeats may supplement this for presence expiration.

Heartbeat frequency must not significantly contribute to load at 500+ connections.

---

# 23. Reconnect Policy

Clients use exponential backoff with jitter.

Conceptual sequence:

```text
250 ms
500 ms
1 s
2 s
4 s
8 s
...
```

with a configurable ceiling.

Reconnect must not generate synchronized retry storms after a server restart.

Jitter is mandatory.

---

# 24. Reconnect State

The frontend preserves:

```text
known syncEpoch
confirmed revision
unacknowledged operations
client operation IDs
```

across temporary socket failure.

---

# 25. Full Resynchronization

The server requests full resynchronization when incremental recovery is unsafe.

Reasons include:

```text
EPOCH_MISMATCH
REVISION_AHEAD
HISTORY_UNAVAILABLE
PROTOCOL_ERROR
PERMISSION_CHANGED
```

The client then reloads canonical state through REST.

---

# 26. WebSocket vs REST

REST remains responsible for:

```text
authentication
document metadata
document list
sharing
initial snapshot
version history
restore
realtime-ticket creation
```

WebSocket remains responsible for:

```text
operations
presence
cursor state
live synchronization
```

Do not add redundant document-body REST autosave calls.

---

# 27. Serialization

Protocol v1 uses JSON because:

* debugging is straightforward,
* browser developer tools can inspect messages,
* Playwright tests can validate payloads,
* implementation complexity remains low.

Possible performance optimization:

```text
binary protocol
```

is deferred.

---

# 28. Binary Protocol Migration

If JSON serialization becomes a measured bottleneck, a future ADR may introduce:

```text
MessagePack
CBOR
Protocol Buffers
custom binary
```

The change must be justified through profiling.

Binary messages should not be introduced merely because they appear theoretically faster.

---

# 29. Ordering

WebSocket preserves frame order on one connection.

However the distributed system must not rely only on transport order.

Canonical document operations contain:

```text
syncEpoch
revision
```

Redis-delivered operations must also be revision checked.

---

# 30. Duplicate Handling

Document messages may be retried or redistributed.

The receiving side checks:

```text
operation identity
revision
```

before applying document changes.

Duplicate presence or cursor events may simply replace existing ephemeral state.

---

# 31. Backpressure

Every WebSocket connection must have bounded outbound and inbound queues.

The implementation must prevent a slow client from consuming unlimited memory.

Possible response to a persistently slow client:

1. drop cursor events,
2. collapse replaceable presence events,
3. preserve canonical operations,
4. disconnect the client if it cannot keep up,
5. require resynchronization.

---

# 32. Message Priority

Conceptual priority:

```text
1. protocol control / permission
2. document operations
3. operation acknowledgement
4. presence
5. cursor movement
```

Ephemeral cursor traffic should never starve document operations.

---

# 33. Message Size

Configurable maximums must exist for:

```text
WebSocket frame size
insert size
operation-group size
```

Oversized content receives a protocol rejection or close.

---

# 34. Error Handling

Application-level recoverable errors use structured protocol messages.

Fatal protocol errors may close the socket.

Do not expose:

```text
stack traces
database errors
Redis credentials
internal class names
```

to the browser.

---

# 35. Close Categories

Clients should distinguish:

```text
normal close
temporary server failure
authentication failure
permission revoked
protocol incompatibility
rate-limit violation
```

Only recoverable categories should automatically reconnect indefinitely.

---

# 36. Server Shutdown

A backend instance should attempt graceful shutdown:

1. stop accepting new sockets,
2. mark itself unready,
3. stop accepting new document leadership,
4. flush committed work,
5. close/migrate active state,
6. close WebSocket connections.

Clients reconnect through the load balancer.

---

# 37. Security

Every socket must be authorized for exactly one document.

A connection cannot change rooms by sending another document ID.

To edit another document:

```text
obtain another ticket
+
open another authorized socket
```

This simplifies the security boundary.

---

# 38. Cross-Site Protection

Production WebSocket handshakes must validate allowed origins.

CORS/WebSocket origin configuration must not simply use:

```text
*
```

in production.

---

# 39. Metrics

Track:

```text
active WebSockets
connections opened/sec
connections closed/sec
connection duration
reconnects
messages/sec
operations/sec
bytes/sec
outbound queue depth
slow clients
protocol errors
cursor messages dropped
```

---

# 40. Required E2E Tests

Antigravity must test:

```text
login → ticket → connect
invalid ticket
expired ticket
ticket reuse
two browsers collaborate
three browsers collaborate
disconnect
reconnect
server restart
permission revoked
version restore
malformed message
slow client
cursor updates
presence join/leave
```

---

# 41. Consequences

## Positive

* direct control over real-time semantics,
* low abstraction overhead,
* easy browser compatibility,
* easy traffic inspection,
* clear performance measurement,
* strong portfolio demonstration.

## Negative

* application must implement its own message schema,
* reconnect logic is custom,
* error handling is custom,
* backpressure must be designed,
* protocol compatibility must be maintained.

---

# 42. Frozen Decisions

ADR-002 freezes:

1. WebSocket as the collaboration transport.
2. Native protocol rather than STOMP.
3. JSON for protocol v1.
4. One document per authorized socket.
5. Short-lived Redis-backed connection tickets.
6. Versioned message envelopes.
7. REST for initial state and administrative actions.
8. WebSocket for live edits.
9. Cursor events as lower-priority best-effort traffic.
10. Exponential reconnect with jitter.

---

# 43. Superseding This ADR

Changing to:

* STOMP,
* SSE,
* polling,
* third-party real-time infrastructure,
* binary-only protocol,

requires a new ADR if the change materially alters these decisions.

