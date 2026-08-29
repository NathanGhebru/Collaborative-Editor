
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

# 12. Document Summary Representation

```json
{
  "id": "f3481704-6158-4eb9-af12-a2865d962edd",
  "title": "Distributed Systems Notes",
  "owner": {
    "id": "9cd819ab-20de-4356-8870-69757480c0d1",
    "displayName": "Nathan"
  },
  "permission": "OWNER",
  "currentRevision": 921,
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "createdAt": "2026-08-28T07:00:00Z",
  "updatedAt": "2026-08-28T07:45:12Z"
}
```

---

# 13. Document Detail Representation

```json
{
  "id": "f3481704-6158-4eb9-af12-a2865d962edd",
  "title": "Distributed Systems Notes",
  "content": "Consensus is...",
  "owner": {
    "id": "9cd819ab-20de-4356-8870-69757480c0d1",
    "displayName": "Nathan"
  },
  "permission": "OWNER",
  "currentRevision": 921,
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "createdAt": "2026-08-28T07:00:00Z",
  "updatedAt": "2026-08-28T07:45:12Z"
}
```

The pair:

```text
syncEpoch
currentRevision
```

identifies the synchronization state represented by `content`.

---

# 14. POST `/documents`

Create a document.

### Request

```json
{
  "title": "Distributed Systems Notes"
}
```

Optional:

```json
{
  "title": "Distributed Systems Notes",
  "initialContent": "Introduction"
}
```

### Success

```http
201 Created
```

```json
{
  "id": "f3481704-6158-4eb9-af12-a2865d962edd",
  "title": "Distributed Systems Notes",
  "content": "Introduction",
  "permission": "OWNER",
  "currentRevision": 0,
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "createdAt": "2026-08-28T07:00:00Z",
  "updatedAt": "2026-08-28T07:00:00Z"
}
```

If `initialContent` is omitted, `content` is the empty string. In both cases the returned content is represented durably by the document's revision-0 snapshot.

### Errors

```text
INVALID_TITLE
DOCUMENT_TOO_LARGE
```

---

# 15. GET `/documents`

List documents accessible by the authenticated user.

### Query Parameters

```text
limit
cursor
```

Example:

```http
GET /api/v1/documents?limit=25
```

### Success

```json
{
  "documents": [
    {
      "id": "f3481704-6158-4eb9-af12-a2865d962edd",
      "title": "Distributed Systems Notes",
      "owner": {
        "id": "9cd819ab-20de-4356-8870-69757480c0d1",
        "displayName": "Nathan"
      },
      "permission": "OWNER",
      "currentRevision": 921,
      "updatedAt": "2026-08-28T07:45:12Z"
    }
  ],
  "nextCursor": null
}
```

Maximum `limit` should be bounded by the server.

---

# 16. GET `/documents/{documentId}`

Retrieve the current document snapshot.

This endpoint is used for:

* initial page load
* full resynchronization
* recovery after epoch mismatch

### Success

```json
{
  "id": "f3481704-6158-4eb9-af12-a2865d962edd",
  "title": "Distributed Systems Notes",
  "content": "Consensus is...",
  "owner": {
    "id": "9cd819ab-20de-4356-8870-69757480c0d1",
    "displayName": "Nathan"
  },
  "permission": "EDITOR",
  "currentRevision": 921,
  "syncEpoch": "a165202b-bac1-431e-9aee-4a6524211454",
  "createdAt": "2026-08-28T07:00:00Z",
  "updatedAt": "2026-08-28T07:45:12Z"
}
```

### Errors

```text
DOCUMENT_NOT_FOUND
DOCUMENT_FORBIDDEN
```

---

# 17. PATCH `/documents/{documentId}`

Update document metadata.

Initial permission:

```text
OWNER or EDITOR
```

Normal body editing does not use this endpoint.

### Request

```json
{
  "title": "Advanced Distributed Systems Notes"
}
```

### Success

```json
{
  "id": "f3481704-6158-4eb9-af12-a2865d962edd",
  "title": "Advanced Distributed Systems Notes",
  "currentRevision": 921,
  "updatedAt": "2026-08-28T08:10:00Z"
}
```

### Errors

```text
DOCUMENT_NOT_FOUND
DOCUMENT_FORBIDDEN
INVALID_TITLE
```

---

# 18. DELETE `/documents/{documentId}`

Delete a document.

Initial permission:

```text
OWNER only
```

### Success

```http
204 No Content
```

Deletion should remove or cascade associated:

* permissions
* operation history
* snapshots
* versions

according to `docs/DATABASE.md`.

Protocol v1 uses hard deletion. The response is sent only after the database transaction commits; active sockets for the deleted document are then closed.

### Errors

```text
DOCUMENT_NOT_FOUND
DOCUMENT_FORBIDDEN
```

---

# 19. Sharing Permission Representation

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

```text
OWNER
EDITOR
```

`OWNER` is represented by document ownership rather than a normal editable permission row.

---

# 20. GET `/documents/{documentId}/permissions`

List document permissions.

Initial permission:

```text
OWNER only
```

### Success

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

### Errors

```text
DOCUMENT_NOT_FOUND
DOCUMENT_FORBIDDEN
```

---

# 21. POST `/documents/{documentId}/permissions`

Share a document.

Initial permission:

```text
OWNER only
```

### Request

A user may be identified by username or email.

```json
{
  "userIdentifier": "collaborator@example.com",
  "role": "EDITOR"
}
```

### Success

```http
201 Created
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

### Errors

```text
DOCUMENT_FORBIDDEN
USER_NOT_FOUND
ALREADY_HAS_ACCESS
INVALID_PERMISSION
```

---

# 22. DELETE `/documents/{documentId}/permissions/{userId}`

Remove a user's access.

Initial permission:

```text
OWNER only
```

### Success

```http
204 No Content
```

If the removed user currently has the document open, active collaboration sockets for that user must be invalidated or disconnected promptly.

### Errors

```text
DOCUMENT_NOT_FOUND
DOCUMENT_FORBIDDEN
PERMISSION_NOT_FOUND
```

---

# 23. POST `/documents/{documentId}/realtime-ticket`

Create a short-lived single-use ticket for opening a document WebSocket.

The endpoint verifies that the user currently has edit permission.

### Request

No body required.

### Success

```json
{
  "ticket": "rt_6DH3...short-lived-secret",
  "expiresAt": "2026-08-28T08:20:30Z",
  "websocketPath": "/ws/v1/documents/f3481704-6158-4eb9-af12-a2865d962edd"
}
```

Recommended ticket lifetime:

```text
30–60 seconds
```

The ticket is invalidated after successful use.

### Errors

```text
DOCUMENT_NOT_FOUND
DOCUMENT_FORBIDDEN
REALTIME_UNAVAILABLE
```

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
