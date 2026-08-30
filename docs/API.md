
# REST API Contract — Real-Time Collaborative Editor

## Status

**Status:** Authentication contract frozen (AUTH-001); document/realtime REST endpoints pending DOC-001
**API version:** v1
**Base path:** `/api/v1`

This file defines the canonical HTTP API.

Once frozen, frontend and backend implementations must follow this contract.

---

# 1. API Principles

The REST API is responsible for:

* authentication
* user information
* document CRUD
* document sharing
* initial document loading
* version history
* version restoration
* creation of short-lived WebSocket tickets

Live collaborative document edits do **not** use REST.

They use the WebSocket protocol defined in:

```text
docs/REALTIME_PROTOCOL.md
```

---

# 2. Content Type

Normal requests and responses use:

```http
Content-Type: application/json
```

unless otherwise specified.

All text must be UTF-8.

---

# 3. Authentication

Protected endpoints require:

```http
Authorization: Bearer <access-token>
```

Access tokens must be short-lived.

Refresh tokens are managed separately through a secure HttpOnly cookie.

---

# 4. Standard Error Response

All API errors follow:

```json
{
  "error": {
    "code": "DOCUMENT_NOT_FOUND",
    "message": "The requested document does not exist.",
    "requestId": "2e84d459-303a-4a6a-9aed-cb06dc36d8da",
    "details": {}
  }
}
```

Fields:

| Field       | Meaning                            |
| ----------- | ---------------------------------- |
| `code`      | Stable machine-readable error code |
| `message`   | Human-readable description         |
| `requestId` | Server request correlation ID      |
| `details`   | Optional structured metadata       |

Clients must use `code` for program logic.

---

# 5. Common Status Codes

| Status | Meaning                            |
| -----: | ---------------------------------- |
|    200 | Success                            |
|    201 | Resource created                   |
|    204 | Success with no response body      |
|    400 | Invalid request                    |
|    401 | Authentication required or invalid |
|    403 | Authenticated but not authorized   |
|    404 | Resource not found                 |
|    409 | Resource/state conflict            |
|    413 | Payload too large                  |
|    422 | Semantically invalid request       |
|    429 | Rate limit exceeded                |
|    500 | Unexpected server error            |
|    503 | Required dependency unavailable    |

---

# 6. User Representation

```json
{
  "id": "9cd819ab-20de-4356-8870-69757480c0d1",
  "username": "nathan",
  "displayName": "Nathan",
  "createdAt": "2026-08-28T07:00:00Z"
}
```

Email may be returned to the currently authenticated user but should not automatically be exposed to other document collaborators.

---

# 7. POST `/auth/register`

Create a user account.

### Request

```json
{
  "username": "nathan",
  "email": "nathan@example.com",
  "password": "example-password",
  "displayName": "Nathan"
}
```

### Success

```http
201 Created
```

```json
{
  "user": {
    "id": "9cd819ab-20de-4356-8870-69757480c0d1",
    "username": "nathan",
    "displayName": "Nathan",
    "createdAt": "2026-08-28T07:00:00Z"
  },
  "accessToken": "<short-lived-token>",
  "expiresInSeconds": 900
}
```

A refresh-token cookie is also issued.

### Errors

```text
USERNAME_TAKEN
EMAIL_TAKEN
INVALID_USERNAME
INVALID_EMAIL
WEAK_PASSWORD
```

---

# 8. POST `/auth/login`

Authenticate an existing user.

### Request

```json
{
  "identifier": "nathan@example.com",
  "password": "example-password"
}
```

`identifier` may accept username or email.

### Success

```json
{
  "user": {
    "id": "9cd819ab-20de-4356-8870-69757480c0d1",
    "username": "nathan",
    "displayName": "Nathan",
    "createdAt": "2026-08-28T07:00:00Z"
  },
  "accessToken": "<short-lived-token>",
  "expiresInSeconds": 900
}
```

### Errors

```text
INVALID_CREDENTIALS
ACCOUNT_DISABLED
RATE_LIMITED
```

Authentication failure messages must not reveal whether a particular email exists.

---

# 9. POST `/auth/refresh`

Create a new access token using the refresh-token cookie.

### Request

No JSON body required.

### Success

```json
{
  "accessToken": "<new-access-token>",
  "expiresInSeconds": 900
}
```

Refresh-token rotation is required.

### Errors

```text
REFRESH_TOKEN_MISSING
REFRESH_TOKEN_INVALID
REFRESH_TOKEN_EXPIRED
REFRESH_TOKEN_REVOKED
```

---

# 10. POST `/auth/logout`

Invalidate the active refresh token.

### Success

```http
204 No Content
```

The refresh-token cookie is cleared.

---

# 11. GET `/users/me`

Return the authenticated user.

### Success

```json
{
  "id": "9cd819ab-20de-4356-8870-69757480c0d1",
  "username": "nathan",
  "email": "nathan@example.com",
  "displayName": "Nathan",
  "createdAt": "2026-08-28T07:00:00Z"
}
```

---

# 12. Document Summary Representation (Frozen - DOC-001)

Represented in document listing endpoints (`GET /api/v1/documents`).

```json
{
  "id": "f3481704-6158-4eb9-af12-a2865d962edd",
  "title": "Distributed Systems Notes",
  "owner": {
    "id": "9cd819ab-20de-4356-8870-69757480c0d1",
    "username": "nathan",
    "displayName": "Nathan"
  },
  "permission": "OWNER",
  "currentRevision": 921,
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "createdAt": "2026-08-28T07:00:00Z",
  "updatedAt": "2026-08-28T07:45:12Z"
}
```

Field rules:
- `id`: Opaque UUID v4 string.
- `title`: Non-empty string, trimmed, 1 to 255 characters.
- `owner`: Summary representation of document creator/owner containing `id`, `username`, and `displayName`.
- `permission`: Role of the requesting user. Values: `"OWNER"`, `"EDITOR"`.
- `currentRevision`: Non-negative 64-bit integer (`0` for newly created documents).
- `syncEpoch`: UUID string representing the active collaboration epoch.
- `createdAt`: ISO 8601 UTC timestamp.
- `updatedAt`: ISO 8601 UTC timestamp.

---

# 13. Document Detail Representation (Frozen - DOC-001)

Represented in single-document endpoints (`POST /api/v1/documents`, `GET /api/v1/documents/{documentId}`).

```json
{
  "id": "f3481704-6158-4eb9-af12-a2865d962edd",
  "title": "Distributed Systems Notes",
  "content": "Consensus is...",
  "owner": {
    "id": "9cd819ab-20de-4356-8870-69757480c0d1",
    "username": "nathan",
    "displayName": "Nathan"
  },
  "permission": "OWNER",
  "currentRevision": 921,
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "createdAt": "2026-08-28T07:00:00Z",
  "updatedAt": "2026-08-28T07:45:12Z"
}
```

Field rules:
- Includes all fields from Document Summary Representation.
- `content`: Plain-text document string (0 to 1,000,000 UTF-16 code units / 1 MB max).
- The pair (`syncEpoch`, `currentRevision`) identifies the exact snapshot version represented by `content`.

---

# 14. POST `/api/v1/documents` (Frozen - DOC-001)

Create a new document.

### Request Body

```json
{
  "title": "Distributed Systems Notes"
}
```

Optional initial content:

```json
{
  "title": "Distributed Systems Notes",
  "initialContent": "Introduction to consensus algorithms."
}
```

Validation constraints:
- `title`: Required string, 1 to 255 characters after trimming (`^[^\r\n]{1,255}$`). Must not contain newlines.
- `initialContent`: Optional string, max 1,000,000 UTF-16 code units (1 MB). If omitted or `null`, defaults to `""` (empty string).

### Success Response

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "id": "f3481704-6158-4eb9-af12-a2865d962edd",
  "title": "Distributed Systems Notes",
  "content": "Introduction to consensus algorithms.",
  "owner": {
    "id": "9cd819ab-20de-4356-8870-69757480c0d1",
    "username": "nathan",
    "displayName": "Nathan"
  },
  "permission": "OWNER",
  "currentRevision": 0,
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "createdAt": "2026-08-28T07:00:00Z",
  "updatedAt": "2026-08-28T07:00:00Z"
}
```

Document creation atomically persists the document row and a revision-0 snapshot in `document_snapshots` within a single transaction.

### Error Responses

- `422 UNPROCESSABLE_ENTITY` (`INVALID_TITLE`): Title missing, empty, blank, or exceeds 255 characters.
- `422 UNPROCESSABLE_ENTITY` (`DOCUMENT_TOO_LARGE`): `initialContent` exceeds 1,000,000 characters.
- `401 UNAUTHENTICATED` (`UNAUTHENTICATED`): Missing or invalid Bearer access token.
- `400 BAD_REQUEST` (`INVALID_REQUEST`): Malformed JSON request body.

---

# 15. GET `/api/v1/documents` (Frozen - DOC-001)

List all documents accessible by the authenticated user (owned documents and explicitly shared documents).

### Query Parameters

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `limit` | integer | `20` | Page size (Min: 1, Max: 100). Bounded by server. |
| `cursor` | string | `null` | Opaque base64url pagination cursor. |

Example request:

```http
GET /api/v1/documents?limit=25
```

### Success Response

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "documents": [
    {
      "id": "f3481704-6158-4eb9-af12-a2865d962edd",
      "title": "Distributed Systems Notes",
      "owner": {
        "id": "9cd819ab-20de-4356-8870-69757480c0d1",
        "username": "nathan",
        "displayName": "Nathan"
      },
      "permission": "OWNER",
      "currentRevision": 921,
      "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
      "createdAt": "2026-08-28T07:00:00Z",
      "updatedAt": "2026-08-28T07:45:12Z"
    }
  ],
  "nextCursor": "eyJ1cGRhdGVkQXQiOiIyMDI2LTA4LTI4VDA3OjQ1OjEyWiIsImlkIjoiZjM0ODE3MDQtNjE1OC00ZWI5LWFmMTItYTI4NjVkOTYyZWRkIn0"
}
```

Pagination ordering:
- Documents are sorted by `updatedAt DESC, id DESC`.
- `nextCursor` is `null` when no further pages exist.

### Error Responses

- `422 UNPROCESSABLE_ENTITY` (`INVALID_CURSOR`): Malformed or unparseable pagination cursor.
- `401 UNAUTHENTICATED` (`UNAUTHENTICATED`): Missing or invalid Bearer access token.

---

# 16. GET `/api/v1/documents/{documentId}` (Frozen - DOC-001)

Retrieve current document snapshot and detail representation.

### Success Response

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "id": "f3481704-6158-4eb9-af12-a2865d962edd",
  "title": "Distributed Systems Notes",
  "content": "Consensus is...",
  "owner": {
    "id": "9cd819ab-20de-4356-8870-69757480c0d1",
    "username": "nathan",
    "displayName": "Nathan"
  },
  "permission": "EDITOR",
  "currentRevision": 921,
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "createdAt": "2026-08-28T07:00:00Z",
  "updatedAt": "2026-08-28T07:45:12Z"
}
```

### Authorization & Concealment Policy

- If the document does not exist OR if the requesting user is neither the owner nor an explicit editor, the server returns `404 DOCUMENT_NOT_FOUND` to conceal private document identifiers.

### Error Responses

- `404 NOT_FOUND` (`DOCUMENT_NOT_FOUND`): Document does not exist or user has no access.
- `401 UNAUTHENTICATED` (`UNAUTHENTICATED`): Missing or invalid Bearer access token.

---

# 17. PATCH `/api/v1/documents/{documentId}` (Frozen - DOC-001)

Update document metadata (such as document title).

Initial required permission: `OWNER` or `EDITOR`.

Note: Text body editing is performed via WebSocket operations, not this HTTP endpoint.

### Request Body

```json
{
  "title": "Advanced Distributed Systems Notes"
}
```

Validation constraints:
- `title`: Required string, 1 to 255 characters after trimming (`^[^\r\n]{1,255}$`). Must not contain newlines.

### Success Response

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "id": "f3481704-6158-4eb9-af12-a2865d962edd",
  "title": "Advanced Distributed Systems Notes",
  "owner": {
    "id": "9cd819ab-20de-4356-8870-69757480c0d1",
    "username": "nathan",
    "displayName": "Nathan"
  },
  "permission": "OWNER",
  "currentRevision": 921,
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "createdAt": "2026-08-28T07:00:00Z",
  "updatedAt": "2026-08-28T08:10:00Z"
}
```

### Error Responses

- `404 NOT_FOUND` (`DOCUMENT_NOT_FOUND`): Document does not exist or requesting user has no access.
- `422 UNPROCESSABLE_ENTITY` (`INVALID_TITLE`): Title missing, empty, or exceeds 255 characters.
- `401 UNAUTHENTICATED` (`UNAUTHENTICATED`): Missing or invalid Bearer access token.

---

# 18. DELETE `/api/v1/documents/{documentId}` (Frozen - DOC-001)

Hard delete a document.

Required permission: `OWNER` only.

### Success Response

```http
HTTP/1.1 204 No Content
```

Deletion behavior:
- Hard deletes the `documents` row and cascades deletion to all associated `document_permissions`, `document_snapshots`, and `document_operation_batches`.
- Active WebSocket connections for the deleted document are closed immediately upon commit.

### Error Responses

- `404 NOT_FOUND` (`DOCUMENT_NOT_FOUND`): Document does not exist or requesting user has no access.
- `403 FORBIDDEN` (`DOCUMENT_FORBIDDEN`): Requesting user is an `EDITOR` (not `OWNER`).
- `401 UNAUTHENTICATED` (`UNAUTHENTICATED`): Missing or invalid Bearer access token.

---

# 19. Sharing Permission Representation (Frozen - DOC-001)

Represented in document permission endpoints.

```json
{
  "user": {
    "id": "7ab5e5a4-ab19-41fd-bac2-4ddfbc88ac31",
    "username": "collaborator",
    "displayName": "Collaborator"
  },
  "role": "EDITOR",
  "createdAt": "2026-08-28T08:15:00Z"
}
```

Supported initial roles:
- `EDITOR`: Full collaborative edit and title edit rights.
- Note: Document `OWNER` is represented on the document object itself rather than a separate permission row.

---

# 20. GET `/api/v1/documents/{documentId}/permissions` (Frozen - DOC-001)

List users granted access to a document.

Required permission: `OWNER` only.

### Success Response

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "owner": {
    "id": "9cd819ab-20de-4356-8870-69757480c0d1",
    "username": "nathan",
    "displayName": "Nathan"
  },
  "permissions": [
    {
      "user": {
        "id": "7ab5e5a4-ab19-41fd-bac2-4ddfbc88ac31",
        "username": "collaborator",
        "displayName": "Collaborator"
      },
      "role": "EDITOR",
      "createdAt": "2026-08-28T08:15:00Z"
    }
  ]
}
```

### Error Responses

- `404 NOT_FOUND` (`DOCUMENT_NOT_FOUND`): Document does not exist or user has no access.
- `403 FORBIDDEN` (`DOCUMENT_FORBIDDEN`): Requesting user is an `EDITOR` (only `OWNER` can view permission lists).
- `401 UNAUTHENTICATED` (`UNAUTHENTICATED`): Missing or invalid Bearer access token.

---

# 21. POST `/api/v1/documents/{documentId}/permissions` (Frozen - DOC-001)

Grant document access to another user.

Required permission: `OWNER` only.

### Request Body

Target user may be identified by username or email.

```json
{
  "userIdentifier": "collaborator@example.com",
  "role": "EDITOR"
}
```

Validation constraints:
- `userIdentifier`: Required non-blank string. Trimming applied.
- `role`: Must be `"EDITOR"`.

### Success Response

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "user": {
    "id": "7ab5e5a4-ab19-41fd-bac2-4ddfbc88ac31",
    "username": "collaborator",
    "displayName": "Collaborator"
  },
  "role": "EDITOR",
  "createdAt": "2026-08-28T08:15:00Z"
}
```

### Error Responses

- `404 NOT_FOUND` (`DOCUMENT_NOT_FOUND`): Document does not exist or requesting user is not owner/editor.
- `403 FORBIDDEN` (`DOCUMENT_FORBIDDEN`): Requesting user is an `EDITOR` (only `OWNER` can grant access).
- `422 UNPROCESSABLE_ENTITY` (`USER_NOT_FOUND`): Target `userIdentifier` does not correspond to any registered user.
- `409 CONFLICT` (`ALREADY_HAS_ACCESS`): Target user is already the document owner or an existing editor.
- `422 UNPROCESSABLE_ENTITY` (`INVALID_ROLE`): Provided role is not `"EDITOR"`.
- `401 UNAUTHENTICATED` (`UNAUTHENTICATED`): Missing or invalid Bearer access token.

---

# 22. DELETE `/api/v1/documents/{documentId}/permissions/{userId}` (Frozen - DOC-001)

Revoke document access from a user.

Required permission: `OWNER` only.

### Success Response

```http
HTTP/1.1 204 No Content
```

Revocation behavior:
- Hard deletes the `document_permissions` record for `userId`.
- If the revoked user currently has an active WebSocket session open, that session is invalidated promptly.

### Error Responses

- `404 NOT_FOUND` (`DOCUMENT_NOT_FOUND`): Document does not exist or requesting user is not owner/editor.
- `403 FORBIDDEN` (`DOCUMENT_FORBIDDEN`): Requesting user is an `EDITOR` (only `OWNER` can revoke access).
- `404 NOT_FOUND` (`PERMISSION_NOT_FOUND`): Target `userId` does not have an active permission record on this document.
- `401 UNAUTHENTICATED` (`UNAUTHENTICATED`): Missing or invalid Bearer access token.

---

# 23. POST `/api/v1/documents/{documentId}/realtime-ticket` (Frozen - RT-001)

Create a short-lived single-use ticket for opening a document WebSocket connection.

Required permission: `OWNER` or `EDITOR`.

### Request

```http
POST /api/v1/documents/f3481704-6158-4eb9-af12-a2865d962edd/realtime-ticket
Authorization: Bearer <access-token>
Content-Type: application/json
```

No JSON body required (`{}` or empty request body).

### Success Response

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "ticket": "rt_6DH39fA28xKq1z9P0mL4vN7yW2rT5uI8",
  "expiresAt": "2026-08-30T12:20:00Z",
  "websocketPath": "/ws/v1/documents/f3481704-6158-4eb9-af12-a2865d962edd"
}
```

Ticket rules & lifecycle:
- `ticket`: High-entropy random token string prefixed with `rt_`.
- `expiresAt`: ISO 8601 UTC timestamp exactly 60 seconds from creation (`TTL = 60s`).
- `websocketPath`: Canonical WebSocket URL path `/ws/v1/documents/{documentId}`.
- Single-use consumption: Atomically invalidated upon WebSocket upgrade.
- Re-use, expiration, or invalid ticket attempt results in HTTP `401 Unauthorized` during the WebSocket handshake.
- Log Redaction: Application servers, reverse proxies (Nginx/ALB), and API gateways MUST redact or omit `ticket` query parameters from HTTP access logs to limit replay exposure. Long-lived Bearer access tokens MUST NEVER be passed in WebSocket query parameters.

### Error Responses

- `404 NOT_FOUND` (`DOCUMENT_NOT_FOUND`): Document does not exist OR requesting user has no access (Concealment policy).
- `401 UNAUTHENTICATED` (`UNAUTHENTICATED`): Missing or invalid Bearer access token.
- `503 SERVICE_UNAVAILABLE` (`REALTIME_UNAVAILABLE`): Realtime coordination subsystem (Redis) unavailable.

---

# 24. WebSocket Connection

A client subsequently opens:

```text
wss://<host>/ws/v1/documents/{documentId}?ticket=<ticket>
```

The detailed protocol is defined in:

```text
docs/REALTIME_PROTOCOL.md
```

---

# 25. GET `/documents/{documentId}/versions`

List retained historical versions.

Initial permission:

```text
OWNER or EDITOR
```

### Query Parameters

```text
limit
cursor
```

### Success

```json
{
  "versions": [
    {
      "id": "72ee2785-a506-4488-9cce-a705739e4d75",
      "versionNumber": 37,
      "sourceRevision": 900,
      "sourceEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
      "createdAt": "2026-08-28T08:00:00Z",
      "createdBy": {
        "id": "9cd819ab-20de-4356-8870-69757480c0d1",
        "displayName": "Nathan"
      },
      "reason": "PERIODIC"
    }
  ],
  "nextCursor": null
}
```

### Errors

```text
DOCUMENT_NOT_FOUND
DOCUMENT_FORBIDDEN
```

Possible reasons:

```text
PERIODIC
MANUAL
PRE_RESTORE
SYSTEM
```

---

# 26. GET `/documents/{documentId}/versions/{versionId}`

Retrieve the content represented by a historical version.

Initial permission:

```text
OWNER or EDITOR
```

### Success

```json
{
  "id": "72ee2785-a506-4488-9cce-a705739e4d75",
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "versionNumber": 37,
  "content": "Historical document contents...",
  "sourceRevision": 900,
  "sourceEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "createdAt": "2026-08-28T08:00:00Z",
  "reason": "PERIODIC"
}
```

### Errors

```text
VERSION_NOT_FOUND
DOCUMENT_FORBIDDEN
```

---

# 27. POST `/documents/{documentId}/versions`

Explicitly create a historical version of the current document.

Initial permission:

```text
OWNER
EDITOR
```

### Request

```json
{
  "label": "Before rewriting introduction"
}
```

`label` is optional.

### Success

```http
201 Created
```

```json
{
  "id": "72ee2785-a506-4488-9cce-a705739e4d75",
  "versionNumber": 38,
  "sourceRevision": 921,
  "sourceEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "label": "Before rewriting introduction",
  "reason": "MANUAL",
  "createdAt": "2026-08-28T08:25:00Z"
}
```

### Errors

```text
DOCUMENT_NOT_FOUND
DOCUMENT_FORBIDDEN
INVALID_VERSION_LABEL
```

---

# 28. POST `/documents/{documentId}/versions/{versionId}/restore`

Restore a historical version.

Initial permission:

```text
OWNER only
```

This is intentionally a consequential operation.

### Request

```json
{
  "expectedCurrentEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "expectedCurrentRevision": 921
}
```

The expectations protect against accidentally restoring over a document that changed after the user opened the history UI.

### Success

```json
{
  "documentId": "f3481704-6158-4eb9-af12-a2865d962edd",
  "syncEpoch": "30235863-e7c2-44b2-a16d-cf137ad8290c",
  "currentRevision": 0,
  "restoredFromVersionId": "72ee2785-a506-4488-9cce-a705739e4d75"
}
```

Restoring:

1. creates a pre-restore version,
2. creates a new synchronization epoch,
3. installs the historical content as revision 0,
4. forces active clients to resynchronize.

### Errors

```text
VERSION_NOT_FOUND
DOCUMENT_FORBIDDEN
RESTORE_CONFLICT
```

A stale expectation returns:

```http
409 Conflict
```

---

# 29. Health Endpoints

Application health endpoints should use Spring Boot Actuator or equivalent internal endpoints.

Examples:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
```

These should not expose secrets or unnecessary infrastructure details publicly.

---

# 30. Request IDs

Every HTTP request should receive a request identifier.

The server should return:

```http
X-Request-ID: <uuid>
```

The same value should appear in structured logs and error responses.

---

# 31. Rate Limits

The API should eventually apply limits to:

* registration attempts
* login attempts
* refresh requests
* document creation
* sharing operations
* real-time ticket creation
* version creation

Rate-limit failures use:

```http
429 Too Many Requests
```

with:

```text
RATE_LIMITED
```

---

# 32. Validation Rules

Server-side validation is mandatory.

Examples:

```text
username length
display-name length
title length
document size
password requirements
valid UUID identifiers
permission enum
version labels
```

The frontend may perform duplicate validation for UX, but frontend validation does not replace server validation.

---

# 33. Security Requirements

The REST API must:

* hash passwords with an approved adaptive password hash,
* never return password hashes,
* never log raw credentials,
* validate authorization server-side,
* rotate refresh tokens,
* revoke refresh tokens on logout,
* reject expired access tokens,
* sanitize unexpected input,
* avoid exposing internal stack traces.

---

# 34. API Error Codes

Expected stable error codes include:

```text
INVALID_REQUEST
UNAUTHENTICATED
FORBIDDEN
RATE_LIMITED

USERNAME_TAKEN
EMAIL_TAKEN
INVALID_USERNAME
INVALID_EMAIL
WEAK_PASSWORD
INVALID_CREDENTIALS
ACCOUNT_DISABLED

REFRESH_TOKEN_MISSING
REFRESH_TOKEN_INVALID
REFRESH_TOKEN_EXPIRED
REFRESH_TOKEN_REVOKED

DOCUMENT_NOT_FOUND
DOCUMENT_FORBIDDEN
INVALID_TITLE
DOCUMENT_TOO_LARGE

USER_NOT_FOUND
ALREADY_HAS_ACCESS
INVALID_PERMISSION
PERMISSION_NOT_FOUND

VERSION_NOT_FOUND
INVALID_VERSION_LABEL
RESTORE_CONFLICT

REALTIME_UNAVAILABLE

INTERNAL_ERROR
DATABASE_UNAVAILABLE
```

Additional codes may be added without breaking existing clients when their meaning is additive.

Default HTTP mappings are:

| Codes | Status |
| --- | ---: |
| `INVALID_REQUEST` | 400 |
| `UNAUTHENTICATED`, invalid/missing/expired/revoked refresh credential, `INVALID_CREDENTIALS` | 401 |
| `FORBIDDEN`, `DOCUMENT_FORBIDDEN`, `ACCOUNT_DISABLED` | 403 |
| `DOCUMENT_NOT_FOUND`, `USER_NOT_FOUND`, `PERMISSION_NOT_FOUND`, `VERSION_NOT_FOUND` | 404 |
| `USERNAME_TAKEN`, `EMAIL_TAKEN`, `ALREADY_HAS_ACCESS`, `RESTORE_CONFLICT` | 409 |
| Invalid field values such as `INVALID_USERNAME`, `INVALID_EMAIL`, `WEAK_PASSWORD`, `INVALID_TITLE`, `INVALID_PERMISSION`, `INVALID_VERSION_LABEL` | 422 |
| `DOCUMENT_TOO_LARGE` | 413 |
| `RATE_LIMITED` | 429 |
| `INTERNAL_ERROR` | 500 |
| `DATABASE_UNAVAILABLE`, `REALTIME_UNAVAILABLE` | 503 |

An endpoint may intentionally return `404` rather than reveal the existence of a resource to an unauthorized caller, but that policy must be consistent across the implementation and security tests.

---

# 35. Frozen Authentication Decisions (AUTH-001)

The following decisions freeze the authentication API behavior for implementation:

### 35.1 Validation and Normalization
* **Username Syntax & Length**: Must match `^[a-zA-Z0-9_]{3,32}$` (ASCII alphanumeric and underscores, 3 to 32 characters). Invalid inputs return `INVALID_USERNAME` (422).
* **Username Normalization**: Trimmed and converted to lower-case for uniqueness checks and database persistence.
* **Email Syntax & Length**: Validated against standard RFC 5322 format; maximum 255 characters. Invalid inputs return `INVALID_EMAIL` (422).
* **Email Normalization**: Trimmed of leading/trailing whitespace and converted to lower-case for uniqueness checks and database persistence.
* **Display Name**: Trimmed UTF-8 string; minimum 1 character, maximum 64 characters.
* **Password Limits**: Minimum 8 characters, maximum 128 characters. Passwords failing length requirements return `WEAK_PASSWORD` (422). Passwords are hashed using BCrypt with a cost factor of 10.

### 35.2 Token Format & Signing
* **Access Tokens**: Short-lived JWTs signed with HMAC-SHA256 (HS256) using a secret key of at least 256 bits supplied via `JWT_SECRET`.
* **Token Claims**: `sub` (user UUID string), `username`, `email`, `iat` (issued timestamp), `exp` (expiration timestamp, default 900 seconds / 15 minutes), `jti` (unique token ID UUID).

### 35.3 Refresh Token Cookie & Revocation
* **Cookie Name**: `rt_token`
* **Cookie Payload**: 256-bit cryptographically secure random token string (43-character Base64URL string). Stored in PostgreSQL as a SHA-256 hex hash (`token_hash`).
* **Cookie Attributes**: `HttpOnly=true`, `Secure=true` in production, `SameSite=Strict`, `Path=/api/v1/auth`, `Max-Age=604800` (7 days).
* **Rotation**: Every call to `POST /auth/refresh` revokes the used refresh token, links `replaced_by_id`, and issues a new refresh token and cookie.
* **Revocation**: `POST /auth/logout` sets `revoked_at` on the refresh token record in the database and clears the `rt_token` cookie (`Max-Age=0`).

### 35.4 Rate Limiting & Security
* **Rate Limits**: `POST /auth/login` and `POST /auth/register` are limited to 5 attempts per minute per IP address / identifier.
* **Rate Limit Exceeded**: Returns HTTP status `429 Too Many Requests` with error code `RATE_LIMITED`.
* **Resource Concealment**: Unauthorized access to document resources returns `404 DOCUMENT_NOT_FOUND` rather than `403` to avoid revealing resource existence.

### 35.5 Remaining Open Decisions
* Document title, display-name, version-label, and initial-content limits remain to be frozen under `DOC-001`.
* OT vector composite details remain to be frozen under `OT-001`.
* Real-time WebSocket limits remain to be frozen under `RT-001`.

---

# 36. API Contract Invariants

The following must remain true:

1. Unauthorized document contents are never returned.
2. Normal collaborative body changes do not use REST writes.
3. `GET /documents/{id}` returns content associated with an explicit epoch and revision.
4. Version restoration creates a new synchronization epoch.
5. WebSocket tickets are document-scoped and short-lived.
6. API clients do not need to understand backend instance topology.
7. Errors have stable machine-readable codes.
8. Backend authorization remains authoritative.

---

# 37. Example Initial Application Flow

```text
POST /auth/login
        ↓
access token
        ↓
GET /documents
        ↓
GET /documents/{id}
        ↓
document snapshot
epoch = E
revision = R
        ↓
POST /documents/{id}/realtime-ticket
        ↓
short-lived ticket
        ↓
WebSocket connect
        ↓
client hello with E / R
        ↓
server catch-up
        ↓
live collaboration
```

This is the canonical transition from REST document loading into real-time collaboration.
