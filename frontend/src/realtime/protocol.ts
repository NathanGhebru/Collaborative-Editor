import { assertOperationShape } from "../ot/operations";
import type { PrimitiveOperation, TextOperation } from "../ot/types";

export const REALTIME_PROTOCOL_VERSION = 1 as const;

export interface RealtimeTicket {
  ticket: string;
  expiresAt: string;
  websocketPath: string;
}

interface Envelope {
  protocolVersion: typeof REALTIME_PROTOCOL_VERSION;
  type: string;
  messageId: string;
  documentId: string;
  timestamp: string;
  payload: Record<string, unknown>;
}

interface EpochEnvelope extends Envelope {
  syncEpoch: string;
}

type ParsedEnvelope = Omit<Envelope, "type"> & { type: string };

export interface ClientHelloMessage extends EpochEnvelope {
  type: "client.hello";
  clientId: string;
  payload: {
    knownEpoch: string;
    knownRevision: number;
  };
}

export interface ClientOperationMessage extends EpochEnvelope {
  type: "client.operation";
  clientId: string;
  payload: {
    clientOperationId: string;
    baseRevision: number;
    operation: PrimitiveOperation;
  };
}

export interface ServerReadyMessage extends EpochEnvelope {
  type: "server.ready";
  payload: {
    connectionId: string;
    revision: number;
    role: "OWNER" | "EDITOR";
  };
}

export interface CanonicalOperationItem {
  revision: number;
  clientId: string;
  clientOperationId: string;
  actorUserId: string;
  operation: TextOperation;
}

export interface ServerOperationsMessage extends EpochEnvelope {
  type: "server.operations";
  payload: {
    operations: CanonicalOperationItem[];
  };
}

export type OperationRejectionCode =
  | "INVALID_OPERATION"
  | "INVALID_POSITION"
  | "INVALID_LENGTH"
  | "INSERT_TOO_LARGE"
  | "DOCUMENT_TOO_LARGE"
  | "IDENTITY_CONFLICT";

export interface ServerOperationRejectedMessage extends EpochEnvelope {
  type: "server.operation_rejected";
  payload: {
    clientOperationId: string;
    code: OperationRejectionCode;
    message: string;
  };
}

export type ResyncReason =
  | "EPOCH_MISMATCH"
  | "REVISION_AHEAD"
  | "HISTORY_UNAVAILABLE"
  | "SERVER_STATE_RECOVERING"
  | "PROTOCOL_ERROR"
  | "PERMISSION_CHANGED";

export interface ServerResyncRequiredMessage extends EpochEnvelope {
  type: "server.resync_required";
  payload: {
    reason: ResyncReason;
  };
}

export interface ServerErrorMessage extends Envelope {
  type: "server.error";
  syncEpoch?: string;
  payload: {
    code: string;
    message: string;
    fatal: boolean;
    closeCode?: number;
  };
}

export interface DeferredPresenceMessage extends Envelope {
  type: "presence.snapshot" | "presence.changed" | "cursor.remote";
  syncEpoch?: string;
}

export type ServerMessage =
  | ServerReadyMessage
  | ServerOperationsMessage
  | ServerOperationRejectedMessage
  | ServerResyncRequiredMessage
  | ServerErrorMessage
  | DeferredPresenceMessage;

export class RealtimeProtocolError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RealtimeProtocolError";
  }
}

export function parseRealtimeTicket(value: unknown): RealtimeTicket {
  const ticket = asObject(value, "Realtime ticket response");
  const token = requiredString(ticket.ticket, "ticket");
  const expiresAt = requiredString(ticket.expiresAt, "expiresAt");
  const websocketPath = requiredString(ticket.websocketPath, "websocketPath");

  if (!token.startsWith("rt_")) {
    throw new RealtimeProtocolError("Realtime ticket must use the rt_ prefix.");
  }
  if (!Number.isFinite(Date.parse(expiresAt))) {
    throw new RealtimeProtocolError("Realtime ticket expiresAt must be an ISO-8601 timestamp.");
  }
  if (!websocketPath.startsWith("/ws/v1/documents/")) {
    throw new RealtimeProtocolError("Realtime ticket websocketPath is not a protocol-v1 document path.");
  }

  return { ticket: token, expiresAt, websocketPath };
}

export function parseServerMessage(raw: string): ServerMessage {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new RealtimeProtocolError("Server frame is not valid JSON.");
  }

  const envelope = asObject(parsed, "Server message");
  if (envelope.protocolVersion !== REALTIME_PROTOCOL_VERSION) {
    throw new RealtimeProtocolError("Server message uses an unsupported protocol version.");
  }

  const type = requiredString(envelope.type, "type");
  const common = {
    protocolVersion: REALTIME_PROTOCOL_VERSION,
    type,
    messageId: requiredString(envelope.messageId, "messageId"),
    documentId: requiredString(envelope.documentId, "documentId"),
    timestamp: requiredString(envelope.timestamp, "timestamp"),
    payload: asObject(envelope.payload, "payload"),
  };

  switch (type) {
    case "server.ready":
      return parseServerReady(common, envelope);
    case "server.operations":
      return parseServerOperations(common, envelope);
    case "server.operation_rejected":
      return parseOperationRejected(common, envelope);
    case "server.resync_required":
      return parseResyncRequired(common, envelope);
    case "server.error":
      return parseServerError(common, envelope);
    case "presence.snapshot":
    case "presence.changed":
    case "cursor.remote":
      return {
        ...common,
        type,
        syncEpoch: optionalString(envelope.syncEpoch, "syncEpoch"),
      };
    default:
      throw new RealtimeProtocolError(`Unsupported server message type: ${type}.`);
  }
}

export function createClientHello(input: {
  messageId: string;
  documentId: string;
  syncEpoch: string;
  clientId: string;
  timestamp: string;
  knownRevision: number;
}): ClientHelloMessage {
  assertRevision(input.knownRevision, "knownRevision", true);
  return {
    protocolVersion: REALTIME_PROTOCOL_VERSION,
    type: "client.hello",
    messageId: input.messageId,
    documentId: input.documentId,
    syncEpoch: input.syncEpoch,
    clientId: input.clientId,
    timestamp: input.timestamp,
    payload: {
      knownEpoch: input.syncEpoch,
      knownRevision: input.knownRevision,
    },
  };
}

export function createClientOperation(input: {
  messageId: string;
  documentId: string;
  syncEpoch: string;
  clientId: string;
  timestamp: string;
  clientOperationId: string;
  baseRevision: number;
  operation: PrimitiveOperation;
}): ClientOperationMessage {
  assertRevision(input.baseRevision, "baseRevision", true);
  assertOperationShape(input.operation);
  if (input.operation.kind !== "INSERT" && input.operation.kind !== "DELETE") {
    throw new RealtimeProtocolError("Clients may transmit only INSERT or DELETE operations.");
  }
  return {
    protocolVersion: REALTIME_PROTOCOL_VERSION,
    type: "client.operation",
    messageId: input.messageId,
    documentId: input.documentId,
    syncEpoch: input.syncEpoch,
    clientId: input.clientId,
    timestamp: input.timestamp,
    payload: {
      clientOperationId: input.clientOperationId,
      baseRevision: input.baseRevision,
      operation: input.operation,
    },
  };
}

function parseServerReady(
  common: ParsedEnvelope,
  envelope: Record<string, unknown>,
): ServerReadyMessage {
  const payload = common.payload;
  const revision = requiredRevision(payload.revision, "server.ready revision", true);
  const role = requiredString(payload.role, "role");
  if (role !== "OWNER" && role !== "EDITOR") {
    throw new RealtimeProtocolError("server.ready role must be OWNER or EDITOR.");
  }
  return {
    ...common,
    type: "server.ready",
    syncEpoch: requiredString(envelope.syncEpoch, "syncEpoch"),
    payload: {
      connectionId: requiredString(payload.connectionId, "connectionId"),
      revision,
      role,
    },
  };
}

function parseServerOperations(
  common: ParsedEnvelope,
  envelope: Record<string, unknown>,
): ServerOperationsMessage {
  const rawOperations = common.payload.operations;
  if (!Array.isArray(rawOperations) || rawOperations.length === 0) {
    throw new RealtimeProtocolError("server.operations must contain a non-empty operations array.");
  }

  const operations = rawOperations.map((value, index) => {
    const item = asObject(value, `operations[${index}]`);
    const operation = item.operation;
    try {
      assertOperationShape(operation);
    } catch (error) {
      throw new RealtimeProtocolError(
        error instanceof Error ? `Invalid canonical operation: ${error.message}` : "Invalid canonical operation.",
      );
    }
    return {
      revision: requiredRevision(item.revision, `operations[${index}].revision`, false),
      clientId: requiredString(item.clientId, `operations[${index}].clientId`),
      clientOperationId: requiredString(
        item.clientOperationId,
        `operations[${index}].clientOperationId`,
      ),
      actorUserId: requiredString(item.actorUserId, `operations[${index}].actorUserId`),
      operation,
    } satisfies CanonicalOperationItem;
  });

  for (let index = 1; index < operations.length; index++) {
    if (operations[index].revision !== operations[index - 1].revision + 1) {
      throw new RealtimeProtocolError("server.operations revisions must be contiguous and ascending.");
    }
  }

  return {
    ...common,
    type: "server.operations",
    syncEpoch: requiredString(envelope.syncEpoch, "syncEpoch"),
    payload: { operations },
  };
}

function parseOperationRejected(
  common: ParsedEnvelope,
  envelope: Record<string, unknown>,
): ServerOperationRejectedMessage {
  const code = requiredString(common.payload.code, "code");
  if (!OPERATION_REJECTION_CODES.has(code as OperationRejectionCode)) {
    throw new RealtimeProtocolError(`Unsupported operation rejection code: ${code}.`);
  }
  return {
    ...common,
    type: "server.operation_rejected",
    syncEpoch: requiredString(envelope.syncEpoch, "syncEpoch"),
    payload: {
      clientOperationId: requiredString(common.payload.clientOperationId, "clientOperationId"),
      code: code as OperationRejectionCode,
      message: requiredString(common.payload.message, "message"),
    },
  };
}

function parseResyncRequired(
  common: ParsedEnvelope,
  envelope: Record<string, unknown>,
): ServerResyncRequiredMessage {
  const reason = requiredString(common.payload.reason, "reason");
  if (!RESYNC_REASONS.has(reason as ResyncReason)) {
    throw new RealtimeProtocolError(`Unsupported resync reason: ${reason}.`);
  }
  return {
    ...common,
    type: "server.resync_required",
    syncEpoch: requiredString(envelope.syncEpoch, "syncEpoch"),
    payload: { reason: reason as ResyncReason },
  };
}

function parseServerError(
  common: ParsedEnvelope,
  envelope: Record<string, unknown>,
): ServerErrorMessage {
  const fatal = common.payload.fatal;
  if (typeof fatal !== "boolean") {
    throw new RealtimeProtocolError("server.error fatal must be a boolean.");
  }
  const closeCode = common.payload.closeCode;
  if (closeCode !== undefined && (!Number.isSafeInteger(closeCode) || (closeCode as number) < 1000)) {
    throw new RealtimeProtocolError("server.error closeCode must be a valid WebSocket close code.");
  }
  return {
    ...common,
    type: "server.error",
    syncEpoch: optionalString(envelope.syncEpoch, "syncEpoch"),
    payload: {
      code: requiredString(common.payload.code, "code"),
      message: requiredString(common.payload.message, "message"),
      fatal,
      ...(closeCode === undefined ? {} : { closeCode: closeCode as number }),
    },
  };
}

function asObject(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new RealtimeProtocolError(`${label} must be an object.`);
  }
  return value as Record<string, unknown>;
}

function requiredString(value: unknown, label: string): string {
  if (typeof value !== "string" || value.length === 0) {
    throw new RealtimeProtocolError(`${label} must be a non-empty string.`);
  }
  return value;
}

function optionalString(value: unknown, label: string): string | undefined {
  return value === undefined ? undefined : requiredString(value, label);
}

function requiredRevision(value: unknown, label: string, allowZero: boolean): number {
  if (typeof value !== "number") {
    throw new RealtimeProtocolError(`${label} must be a number.`);
  }
  assertRevision(value, label, allowZero);
  return value;
}

function assertRevision(value: number, label: string, allowZero: boolean): void {
  const minimum = allowZero ? 0 : 1;
  if (!Number.isSafeInteger(value) || value < minimum) {
    throw new RealtimeProtocolError(`${label} must be a safe integer >= ${minimum}.`);
  }
}

const OPERATION_REJECTION_CODES = new Set<OperationRejectionCode>([
  "INVALID_OPERATION",
  "INVALID_POSITION",
  "INVALID_LENGTH",
  "INSERT_TOO_LARGE",
  "DOCUMENT_TOO_LARGE",
  "IDENTITY_CONFLICT",
]);

const RESYNC_REASONS = new Set<ResyncReason>([
  "EPOCH_MISMATCH",
  "REVISION_AHEAD",
  "HISTORY_UNAVAILABLE",
  "SERVER_STATE_RECOVERING",
  "PROTOCOL_ERROR",
  "PERMISSION_CHANGED",
]);
