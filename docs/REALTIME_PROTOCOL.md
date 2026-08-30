# Real-Time Collaboration Protocol

## Status

**Status:** Frozen (RT-001)  
**Protocol version:** `1`  
**Transport:** WebSocket  
**Synchronization:** Server-authoritative Operational Transformation  

This document defines the canonical browser-to-server collaboration semantics for protocol v1. Redis-internal routing is a separate internal contract governed by ADR-004 and is not part of the public WebSocket protocol.

Once frozen, neither frontend nor backend implementation may independently reinterpret this protocol.

---

# 1. WebSocket Endpoint

```text
/ws/v1/documents/{documentId}
```

Connection example:

```text
wss://example.com/ws/v1/documents/f3481704-6158-4eb9-af12-a2865d962edd?ticket=<ticket>
```

The ticket is obtained from:

```http
POST /api/v1/documents/{documentId}/realtime-ticket
```

---

# 2. Ticket Requirements

A real-time ticket is:

* short-lived (`TTL = 60s`),
* single-use (atomically deleted from Redis on handshake via `GETDEL`),
* user-scoped (`userId`),
* document-scoped (`documentId`),
* permission-scoped (`OWNER` or `EDITOR`).

The server rejects:

* expired tickets,
* already-used tickets,
* tickets for another document,
* tickets from users with revoked document permissions.

Rejection during handshake returns HTTP `401 Unauthorized` / WS close code `4001` (`UNAUTHORIZED`). Successful handshake consumes the ticket.

The path document is the authorized room. If a message envelope contains a `documentId`, it must equal the path document or the server treats the message as a protocol violation and closes the socket; a client cannot switch rooms on an existing socket.

---

# 3. Message Encoding

Protocol v1 uses UTF-8 JSON text frames exclusively. Binary frames are rejected with WS close code `4003` (`UNSUPPORTED_DATA`).

All application messages contain:

```json
{
  "protocolVersion": 1,
  "type": "message.type",
  "messageId": "2037cead-f834-46ab-a557-478a24027067",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "timestamp": "2026-08-30T12:15:00.000Z",
  "payload": {}
}
```

Unknown fields in JSON envelopes are ignored by parsers (forward compatibility rule).

---

# 4. Common Fields

Where applicable, messages contain:

| Field | Type | Description |
| --- | --- | --- |
| `protocolVersion` | integer | Fixed at `1` for protocol v1 |
| `type` | string | Message event type discriminator |
| `messageId` | string (UUID v4) | Transport correlation ID for diagnostics/logging |
| `documentId` | string (UUID v4) | Target document UUID |
| `syncEpoch` | string (UUID v4) | Synchronization epoch UUID |
| `clientId` | string (UUID v4) | Originating browser client UUID |
| `timestamp` | string (ISO 8601 UTC) | Message creation timestamp |
| `payload` | object | Message-specific payload DTO |

---

# 5. Client Identity

A browser installation/session generates a stable:

```text
clientId
```

UUID.

Each WebSocket connection receives a server-generated:

```text
connectionId
```

A single user may have multiple:

```text
clientId
connectionId
```

values through multiple browsers or tabs.

---

# 6. Client Operation Identity

Every edit operation has:

```text
clientOperationId
```

The unique idempotency identity is:

```text
documentId
+ syncEpoch
+ clientId
+ clientOperationId
```

A retry must reuse the original ID.

A client must not generate a new operation ID merely because an acknowledgement timed out.

---

# 7. Initial Connection Sequence

The normal startup flow is:

```text
REST load document
        ↓
content + epoch E + revision R
        ↓
obtain real-time ticket
        ↓
open WebSocket
        ↓
client.hello(E, R)
        ↓
server synchronization response
        ↓
presence snapshot
        ↓
server.ready
        ↓
live collaboration
```

---

# 8. `client.hello`

The first application message from a newly connected client.

```json
{
  "protocolVersion": 1,
  "type": "client.hello",
  "messageId": "2037cead-f834-46ab-a557-478a24027067",
  "clientId": "7a719fbf-87ce-408d-beac-c665df880eaf",
  "payload": {
    "knownEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
    "knownRevision": 921
  }
}
```

No edit messages may be processed until synchronization completes.

---

# 9. `server.ready`

Sent after successful authentication and synchronization.

The initial `presence.snapshot` is sent before `server.ready`; receipt of `server.ready` is the sole signal that edit transmission may begin.

```json
{
  "protocolVersion": 1,
  "type": "server.ready",
  "messageId": "408f4163-a915-4830-8a5e-f86414db625c",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "connectionId": "6cca533d-aa28-4624-b0d9-f8078ed2e4d2",
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "revision": 924,
  "payload": {}
}
```

After this message the client may submit operations.

---

# 10. Initial Delta Catch-Up

Suppose:

```text
client revision = 921
server revision = 924
epoch matches
```

The server may send revisions:

```text
922
923
924
```

before `server.ready`.

---

# 11. `server.operations`

Carries one or more accepted canonical operations.

```json
{
  "protocolVersion": 1,
  "type": "server.operations",
  "messageId": "5d61be99-3907-4291-b65d-0d249b23eb76",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "payload": {
    "operations": [
      {
        "revision": 922,
        "clientId": "6a670f82-bf16-403d-82bd-69ad26476108",
        "clientOperationId": "18e5a1c0-71e4-4b05-be54-f03826edaf33",
        "actorUserId": "7ab5e5a4-ab19-41fd-bac2-4ddfbc88ac31",
        "operation": {
          "kind": "INSERT",
          "position": 4,
          "text": "hello"
        }
      }
    ]
  }
}
```

Operations must be ordered by ascending canonical revision.

---

# 12. Full Resynchronization

A full resync is required if:

* the synchronization epoch differs,
* client revision is invalid,
* required operation history is unavailable through normal catch-up,
* protocol state becomes inconsistent.

The server sends:

```json
{
  "protocolVersion": 1,
  "type": "server.resync_required",
  "messageId": "3d104027-ad8e-4ffe-a317-913a17bc1180",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "payload": {
    "reason": "EPOCH_MISMATCH"
  }
}
```

The client then:

1. pauses edit transmission,
2. preserves local unacknowledged intent where recoverable,
3. reloads the document using REST,
4. reconciles or replays valid local edits,
5. obtains a new ticket if necessary,
6. reconnects.

---

# 13. Resync Reasons

Possible reasons include:

```text
EPOCH_MISMATCH
REVISION_AHEAD
HISTORY_UNAVAILABLE
SERVER_STATE_RECOVERING
PROTOCOL_ERROR
PERMISSION_CHANGED
```

---

# 14. Operation Envelope

A client edit message uses:

```json
{
  "protocolVersion": 1,
  "type": "client.operation",
  "messageId": "3a459e4b-1bd0-4686-8aa0-a6315c5ec548",
  "clientId": "7a719fbf-87ce-408d-beac-c665df880eaf",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "payload": {
    "clientOperationId": "a21acdd4-2248-485d-ac37-5cbbfdc13ccb",
    "baseRevision": 924,
    "operation": {
      "kind": "INSERT",
      "position": 19,
      "text": "distributed "
    }
  }
}
```

---

# 15. Insert Operation

```json
{
  "kind": "INSERT",
  "position": 19,
  "text": "distributed "
}
```

Requirements:

```text
position >= 0
position <= document length
text is non-empty
text size <= configured maximum
```

Positions and lengths use UTF-16 code units, as frozen by ADR-001.

Surrogate-Pair Policy:
- Positions and deletion boundaries must not bisect UTF-16 surrogate pairs (`0xD800`–`0xDBFF` high surrogate followed by `0xDC00`–`0xDFFF` low surrogate).
- If an operation specifies a position or boundary between a surrogate pair, it is rejected with `INVALID_POSITION`.
- The Java and TypeScript implementations must use identical UTF-16 indexing and validation semantics.

---

# 16. Delete Operation

```json
{
  "kind": "DELETE",
  "position": 19,
  "length": 12
}
```

Requirements:

```text
position >= 0
length > 0
position + length <= document length
```

Neither `position` nor `position + length` may split a UTF-16 surrogate pair.

## 16.1 Server-Emitted `NO_OP`

Transformation may remove the effect of an otherwise valid client operation, for example when its entire delete range was already deleted concurrently. The server can emit:

```json
{
  "kind": "NO_OP"
}
```

`NO_OP` is a canonical server result, consumes one revision, and permits durable acknowledgement and idempotent retry. Clients must not submit `NO_OP` as a new edit.

---

# 17. Server-Emitted Operation Group

A logical operation emitted by the server may consist of multiple primitives executed sequentially.

Example split deletion resulting from insert-wins transformation:

```json
{
  "kind": "GROUP",
  "operations": [
    {
      "kind": "DELETE",
      "position": 10,
      "length": 5
    },
    {
      "kind": "DELETE",
      "position": 18,
      "length": 5
    }
  ]
}
```

Semantics:
* A server-emitted group represents one logical operation committed atomically under one canonical revision.
* Primitives within the group are executed sequentially (each primitive operates on the state produced by prior primitives in the group).
* Client-authored `GROUP` is disabled for protocol v1. Clients transmit individual primitive operations; if a client transmits a `GROUP`, the server rejects it with `INVALID_OPERATION`.

---

# 18. Canonical Revision

Only the active document leader may assign a canonical revision.

Example:

```text
current revision = 924
```

After accepting one operation:

```text
new revision = 925
```

Revisions are strictly increasing within one synchronization epoch.

---

# 19. Stale Operation

A client may submit:

```text
baseRevision = 920
```

while the leader currently has:

```text
revision = 924
```

The leader loads canonical operations:

```text
921
922
923
924
```

and transforms the incoming operation over each one.

The resulting transformed operation is applied to the current document and becomes revision:

```text
925
```

---

# 20. Future Revision

If:

```text
baseRevision > server currentRevision
```

the client is inconsistent.

The operation is rejected and a resynchronization is required.

---

# 21. Wrong Epoch

If:

```text
client syncEpoch != server syncEpoch
```

the operation must not be transformed or accepted.

The client receives:

```text
EPOCH_MISMATCH
```

and must resynchronize.

---

# 22. Operation Acknowledgement

The origin client receives the same canonical accepted operation as other clients, followed on that socket by a compact acknowledgement:

```json
{
  "protocolVersion": 1,
  "type": "server.operation_ack",
  "messageId": "cb047817-7aa3-43dc-a96f-e1563150ed44",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "revision": 925,
  "payload": {
    "clientOperationId": "a21acdd4-2248-485d-ac37-5cbbfdc13ccb"
  }
}
```

The client may remove the corresponding operation from its pending queue only after acceptance is confirmed.

---

# 23. Idempotent Retry

If the client retransmits an already accepted:

```text
(documentId, syncEpoch, clientId, clientOperationId)
```

the server does not apply it again.

The server returns the previously assigned canonical result.

---

# 24. Operation Rejection & Error Messages

When an edit operation is invalid, the server sends `server.operation_rejected` on that client's socket. The connection remains OPEN.

```json
{
  "protocolVersion": 1,
  "type": "server.operation_rejected",
  "messageId": "1f547a58-f530-46b3-b550-a8fe9072417f",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "timestamp": "2026-08-30T12:15:00.052Z",
  "payload": {
    "clientOperationId": "a21acdd4-2248-485d-ac37-5cbbfdc13ccb",
    "code": "INVALID_POSITION",
    "message": "Insert position 150 exceeds current document length 100."
  }
}
```

When a protocol or connection-level error occurs, the server sends `server.error`:

```json
{
  "protocolVersion": 1,
  "type": "server.error",
  "messageId": "8f12ce43-9821-4b12-a42e-1178a9c40211",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "timestamp": "2026-08-30T12:15:00.053Z",
  "payload": {
    "code": "UNAUTHENTICATED",
    "message": "Realtime ticket has expired or is invalid.",
    "fatal": true,
    "closeCode": 4001
  }
}
```

If `fatal` is `true`, the server closes the WebSocket immediately after sending `server.error`.

---

# 25. Error & Close Code Specification

### 25.1 Rejection & Error Codes

| Code | Level | Fatal | Socket Action | Client Action |
| --- | --- | --- | --- | --- |
| `UNAUTHENTICATED` | Protocol | Yes | Close `4001` | Obtain new ticket & reconnect |
| `DOCUMENT_FORBIDDEN` | Protocol | Yes | Close `4001` | Stop; show permission error |
| `INVALID_MESSAGE` | Protocol | Yes/No | Close `4000` if frame | Discard message or fix envelope |
| `UNSUPPORTED_PROTOCOL_VERSION` | Protocol | Yes | Close `4002` | Stop; update client |
| `INVALID_OPERATION` | Operation | No | Keep Open | Drop operation; notify user |
| `INVALID_POSITION` | Operation | No | Keep Open | Drop operation |
| `INVALID_LENGTH` | Operation | No | Keep Open | Drop operation |
| `INSERT_TOO_LARGE` | Operation | No | Keep Open | Drop operation |
| `DOCUMENT_TOO_LARGE` | Operation | No | Keep Open | Drop operation |
| `IDENTITY_CONFLICT` | Operation | No | Keep Open | Drop operation (different payload reuse) |
| `EPOCH_MISMATCH` | Synchronization | No | Send `server.resync_required` | Reload REST snapshot & resync |
| `REVISION_AHEAD` | Synchronization | No | Send `server.resync_required` | Reload REST snapshot & resync |
| `RATE_LIMIT_EXCEEDED` | Protocol | Yes | Close `4008` | Backoff & reconnect |
| `DOCUMENT_NOT_FOUND` | Protocol | Yes | Close `4003` | Stop; notify user |
| `INTERNAL_ERROR` | System | No | Keep Open | Retry operation |

### 25.2 Numeric WebSocket Close Codes (RFC 6455 Range 4000–4999)

| Close Code | Constant | Meaning | Reconnect Category |
| ---: | --- | --- | --- |
| `4000` | `BAD_REQUEST` | Malformed message frame or envelope | Non-retryable |
| `4001` | `UNAUTHORIZED` | Invalid/expired ticket or permission revoked | Re-authenticate via REST |
| `4002` | `UNSUPPORTED_PROTOCOL_VERSION` | Client `protocolVersion != 1` | Non-retryable |
| `4003` | `DOCUMENT_DELETED` | Target document hard deleted | Non-retryable |
| `4004` | `SESSION_SUPERSEDED` | Connection replaced by newer session | Non-retryable |
| `4008` | `RATE_LIMIT_EXCEEDED` | Exceeded message or connection rate limit | Backoff reconnect |
| `4009` | `PAYLOAD_TOO_LARGE` | Frame or operation exceeded max bounds | Non-retryable |

---

# 26. OT Transformation Semantics

The canonical implementation must define:

```text
transform(A, B)
```

meaning:

> Transform operation A so that it preserves A's intended effect after concurrent operation B has already been applied.

The transformation implementation must be identical in semantics in:

```text
Java backend
TypeScript client
```

---

# 27. Insert vs Insert

Let $A = \text{INSERT}(a, \text{text}_A)$ and $B = \text{INSERT}(b, \text{text}_B)$ with $l_B = \text{length}(\text{text}_B)$:
- If $a < b$: $A' = \text{INSERT}(a, \text{text}_A)$
- If $a > b$: $A' = \text{INSERT}(a + l_B, \text{text}_A)$
- If $a == b$: Tie-breaking compares $(A.\text{clientId}, A.\text{clientOperationId})$ vs $(B.\text{clientId}, B.\text{clientOperationId})$ lexicographically:
  - If $K_A < K_B$ ($A$ has precedence): $A' = \text{INSERT}(a, \text{text}_A)$
  - If $K_A > K_B$ ($B$ has precedence): $A' = \text{INSERT}(a + l_B, \text{text}_A)$

---

# 28. Insert vs Delete

Let $A = \text{INSERT}(a, \text{text}_A)$ and $B = \text{DELETE}(b, l_B)$ with $end_B = b + l_B$:
- If $a \le b$: $A' = \text{INSERT}(a, \text{text}_A)$
- If $a \ge end_B$: $A' = \text{INSERT}(a - l_B, \text{text}_A)$
- If $b < a < end_B$: Under insert-wins policy, the insert survives and collapses to the deletion start position:
  $A' = \text{INSERT}(b, \text{text}_A)$

---

# 29. Delete vs Insert

Let $A = \text{DELETE}(a, l_A)$ and $B = \text{INSERT}(b, \text{text}_B)$ with $end_A = a + l_A$ and $l_B = \text{length}(\text{text}_B)$:
- If $b \le a$: $A' = \text{DELETE}(a + l_B, l_A)$
- If $b \ge end_A$: $A' = \text{DELETE}(a, l_A)$
- If $a < b < end_A$: Under insert-wins policy, the concurrent insertion survives. $A$ splits into a sequential `GROUP` of two deletions around $B$:
  $$A' = \text{GROUP}([\text{DELETE}(a, b - a), \text{DELETE}(a + l_B, end_A - b)])$$

---

# 30. Delete vs Delete

Let $A = \text{DELETE}(a, l_A)$ and $B = \text{DELETE}(b, l_B)$ with $end_A = a + l_A$ and $end_B = b + l_B$:
- If $end_B \le a$: $A' = \text{DELETE}(a - l_B, l_A)$
- If $b \ge end_A$: $A' = \text{DELETE}(a, l_A)$
- If overlapping:
  - $overlap = \max(0, \min(end_A, end_B) - \max(a, b))$
  - $newLength = l_A - overlap$
  - If $newLength == 0$: $A' = \text{NO\_OP}$
  - Else if $a < b$:
    - If $end_A \le end_B$: $A' = \text{DELETE}(a, newLength)$
    - If $end_A > end_B$: $A' = \text{DELETE}(a, l_A - l_B)$
  - Else ($a \ge b$):
    - $A' = \text{DELETE}(b, newLength)$

---

# 31. Client Pending Queue and Rebase Model

The client implements a 3-state pending model with a single in-flight operation over WebSocket and a local sequential buffer:

1. **State 1: Synchronized** (`inFlight == null`, buffer empty).
2. **State 2: Awaiting In-Flight** (operation $A$ in flight with `baseRevision = confirmedRevision`, buffer empty).
3. **State 3: Awaiting with Buffer** (operation $A$ in flight, local edits $B_1 \dots B_n$ queued locally and not yet transmitted).

When remote canonical operation $R$ arrives at `confirmedRevision + 1`:
- $R_0 = transform(R, A)$, $A' = transform(A, R)$.
- For $i = 1 \dots n$:
  $R_i = transform(R_{i-1}, B_i)$, $B_i' = transform(B_i, R_{i-1})$.
- Apply $R$ to confirmed document; advance `confirmedRevision = R.revision`.
- Replace in-flight with $A'$; replace local buffer with $[B_1', \dots, B_n']$.
- Apply $R_n$ to visible optimistic document.

When acknowledgement for $A$ arrives:
- Clear in-flight operation.
- If buffer is non-empty, dequeue first buffered operation $B_1$ (or composed buffer), set `baseRevision = confirmedRevision`, set as new in-flight, and transmit `client.operation`.

---

# 32. Presence Join

After synchronization, the server sends the current document presence snapshot.

```json
{
  "protocolVersion": 1,
  "type": "presence.snapshot",
  "messageId": "1956142a-05cf-44d7-bd55-b8b06937b6c5",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "payload": {
    "users": [
      {
        "userId": "7ab5e5a4-ab19-41fd-bac2-4ddfbc88ac31",
        "displayName": "Collaborator",
        "connections": 1
      }
    ]
  }
}
```

---

# 33. Presence Changed

```json
{
  "protocolVersion": 1,
  "type": "presence.changed",
  "messageId": "78992be8-e515-4e29-bd85-8169729166f4",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "payload": {
    "event": "JOINED",
    "user": {
      "userId": "7ab5e5a4-ab19-41fd-bac2-4ddfbc88ac31",
      "displayName": "Collaborator"
    }
  }
}
```

Events:

```text
JOINED
LEFT
UPDATED
```

---

# 34. Cursor Update

Client message:

```json
{
  "protocolVersion": 1,
  "type": "cursor.update",
  "messageId": "7ac1e178-cb58-42f6-9934-a2c8f89ac753",
  "clientId": "7a719fbf-87ce-408d-beac-c665df880eaf",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "payload": {
    "baseRevision": 925,
    "anchor": 20,
    "head": 25
  }
}
```

A collapsed cursor has:

```text
anchor == head
```

A selection has:

```text
anchor != head
```

---

# 35. Remote Cursor Event

```json
{
  "protocolVersion": 1,
  "type": "cursor.remote",
  "messageId": "cfc3627e-f050-45ec-aad5-9b38b5f9526a",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "revision": 925,
  "payload": {
    "userId": "7ab5e5a4-ab19-41fd-bac2-4ddfbc88ac31",
    "displayName": "Collaborator",
    "connectionId": "f91d4340-9631-44e3-be94-35fb35546a21",
    "anchor": 20,
    "head": 25
  }
}
```

The receiving client may transform the cursor positions over its local unacknowledged operations.

---

# 36. Cursor Rate Limiting

Cursor updates are best-effort ephemeral messages.

Clients should throttle cursor transmission.

Initial target:

```text
<= 20 cursor updates/sec/connection
```

The server may drop excess cursor updates rather than allowing them to compete with document edits.

---

# 37. Heartbeat

WebSocket-level ping/pong may be used.

The application may additionally use:

```json
{
  "protocolVersion": 1,
  "type": "client.heartbeat",
  "messageId": "c1668846-4711-4f15-87cb-913bef0a91e7"
}
```

where needed for presence expiration.

---

# 38. Permission Revocation

If a user's document permission is removed while connected, the server sends:

```json
{
  "protocolVersion": 1,
  "type": "server.permission_revoked",
  "messageId": "22198f1e-b570-4da8-9105-8f9cc45ab4c8",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd"
}
```

The server then closes the connection.

Further edits from the user are rejected.

---

# 39. Document Reset

Version restore creates a new synchronization epoch.

Connected clients receive:

```json
{
  "protocolVersion": 1,
  "type": "server.document_reset",
  "messageId": "8796fbcd-c456-489a-844a-df011925e293",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "payload": {
    "newSyncEpoch": "30235863-e7c2-44b2-a16d-cf137ad8290c",
    "reason": "VERSION_RESTORED"
  }
}
```

Clients must stop normal operation submission and fully resynchronize.

---

# 40. Reconnect

The client uses exponential backoff with jitter.

Example progression:

```text
250 ms
500 ms
1 s
2 s
4 s
...
```

with a reasonable maximum.

The UI must visibly indicate prolonged reconnect state.

---

# 41. Unacknowledged Operations During Disconnect

The client retains unacknowledged operations.

After reconnect and synchronization:

* already accepted operations are detected by operation ID,
* unaccepted operations are rebased against newer canonical history,
* valid operations are retransmitted with the same IDs.

The client must not blindly discard user edits after a transient disconnect.

Automatic replay is defined only when the operation remains in the same epoch and the canonical history required to rebase it is available. When an epoch changed or required history is unavailable, the client must preserve the user's local text/intent for explicit recovery and must not claim it was saved. The exact recovery UX and any safe translation rule remain a protocol-freeze blocker.

---

# 42. Internal Redis Boundary

Redis messages are not browser protocol messages. Their routing purposes and reliability assumptions are defined by ADR-004. Concrete internal schemas must be separately versioned during the Redis implementation task and must not be exposed as public WebSocket event types.

---

# 43. Cross-Instance Gap Recovery

Every accepted operation event includes:

```text
syncEpoch
revision
```

If an instance expects revision:

```text
101
```

but receives:

```text
103
```

it must retrieve:

```text
101
102
```

or the missing subset from PostgreSQL before advancing.

It must never infer that missed Redis messages were unimportant.

---

# 44. Persistence Failure

If the leader cannot persist an operation batch:

* affected operations are not committed,
* they are not given successful final acknowledgements,
* the room may enter degraded/recovering state,
* clients may be asked to retry.

Correctness takes priority over pretending an edit was saved.

---

# 45. Protocol Limits

The server enforces the following protocol-v1 bounds:

| Limit Parameter | Frozen Bound | Exceeded Action |
| --- | --- | --- |
| Max WebSocket Frame Size | 65,536 bytes (64 KB) | WS Close `4009` (`PAYLOAD_TOO_LARGE`) |
| Max Single Insert Length | 10,000 UTF-16 code units | `server.operation_rejected` (`INSERT_TOO_LARGE`) |
| Max Document Size | 1,000,000 UTF-16 code units (1 MB) | `server.operation_rejected` (`DOCUMENT_TOO_LARGE`) |
| Max Client Pending Queue | 1 in-flight + local sequential buffer | Client throttle |
| Max Cursor Update Rate | 20 updates/sec/connection | Ephemeral cursor drop |
| Ticket Expiration TTL | 60 seconds | REST/WS HTTP `401` / Close `4001` |

---

# 46. Protocol Close Conditions

The server closes the WebSocket for:

* `4000` (`BAD_REQUEST`): Malformed JSON frame, missing envelope fields, or invalid structure.
* `4001` (`UNAUTHORIZED`): Expired/invalid ticket, ticket reuse, or user document permission revoked.
* `4002` (`UNSUPPORTED_PROTOCOL_VERSION`): Requested `protocolVersion != 1`.
* `4003` (`DOCUMENT_DELETED`): Document hard deleted via REST.
* `4004` (`SESSION_SUPERSEDED`): Connection replaced by a newer session for the same client.
* `4008` (`RATE_LIMIT_EXCEEDED`): Repeated rate limit or frame rate abuse.
* `4009` (`PAYLOAD_TOO_LARGE`): Frame size > 64 KB.

Clients must distinguish recoverable closes (e.g. transient disconnects) from authorization failures (`4001`) which require re-authenticating via REST.

---

# 47. Correctness Invariants

The protocol must preserve:

1. Operations have stable identities (`documentId + syncEpoch + clientId + clientOperationId`).
2. Retries do not duplicate edits.
3. Canonical revisions are strictly ordered.
4. Wrong-epoch operations are never accepted.
5. Clients can recover from missed Redis events.
6. Clients can reconnect without blindly overwriting newer state.
7. Presence/cursor loss cannot corrupt document content.
8. Permission changes are enforced server-side.
9. Persistence failure cannot produce a false successful save acknowledgement.
10. All active clients eventually converge when communication succeeds.

---

# 48. Protocol Status and Open Decisions

The OT core synchronization blockers were resolved under **OT-001**:
1. **Multiple pending local operations:** Frozen to single in-flight operation + local sequential buffer model with progressive rebase (Section 31; ADR-001 Sections 23–24).
2. **Composite GROUP transformation:** Frozen to sequential execution semantics with client-authored GROUP deferred for v1 and server-emitted split DELETEs fully specified (Sections 17, 29; ADR-001 Section 21).

The public real-time protocol blockers are resolved under **RT-001**:
3. **WebSocket Ticket & Auth Flow:** Frozen to REST ticket acquisition (`POST /api/v1/documents/{documentId}/realtime-ticket`), 60s TTL, single-use Redis deletion (`GETDEL`), and ticket query parameter `?ticket=<ticket>` (Section 2; ADR-002 Section 9–10).
4. **Numeric WebSocket Close Codes & Reconnect Categories:** Frozen to application range 4000–4009 with explicit non-retryable vs re-authenticate classifications (Section 25.2).
5. **Exact Message Envelopes & Dual-Ack Delivery:** Frozen to `client.operation` $\rightarrow$ `server.operations` broadcast to all subscribers + `server.operation_ack` to origin client socket (Sections 11, 14, 22).
6. **Configurable Frame & Size Limits:** Frozen to 64 KB max frame size, 10,000 UTF-16 code units max insert, and 1,000,000 UTF-16 code units max document size (Section 45).
7. **Machine-Readable Fixtures:** Frozen in `docs/realtime-protocol-fixtures.json`.

Downstream roadmap task assignments:
- Preservation and user recovery of unacknowledged intent across epoch changes (version restore): Assigned to `HIST-001`.

---

# 49. Required Protocol Tests

Automated tests must cover:

```text
two concurrent inserts
same-position inserts
insert/delete races
overlapping deletes
server-generated composite delete
client-authored GROUP accepted or rejected according to OT-001
duplicate operation retry
stale base revision
future base revision
wrong epoch
disconnect before acknowledgement
disconnect after server commit
reconnect with pending operation
duplicate Redis accepted event
missing Redis revision
leader failure
leader takeover
permission revocation
version restore
cursor movement
multi-instance collaboration
```

The protocol is not considered frozen until these behaviors have unambiguous expected outcomes.

---

# 50. Protocol Summary

The real-time path is:

```text
local edit
    ↓
client operation + base revision
    ↓
WebSocket instance
    ↓
document leader
    ↓
transform against canonical history
    ↓
assign provisional revision and persist
    ↓
commit canonical revision
    ↓
Redis propagation
    ↓
all connected clients
    ↓
client-side rebase
    ↓
convergence
```

---

---
