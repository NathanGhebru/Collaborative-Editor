import { applyOperation, assertOperationValidForDocument } from "../ot/operations";
import { rebasePendingOperations } from "../ot/rebase";
import type { IdentifiedOperation, PrimitiveOperation, TextOperation } from "../ot/types";
import type { UuidFactory } from "./clientId";
import {
  createClientHello,
  createClientOperation,
  parseServerMessage,
  RealtimeProtocolError,
  type CanonicalOperationItem,
  type ServerErrorMessage,
  type ServerOperationRejectedMessage,
  type ServerOperationsMessage,
  type ServerReadyMessage,
  type ServerResyncRequiredMessage,
} from "./protocol";
import {
  buildRealtimeWebSocketUrl,
  type RealtimeTransport,
  type RealtimeTransportHandlers,
} from "./transport";
import type { RealtimeTicketProvider } from "./ticketApi";

export type CollaborationStatus =
  | "disconnected"
  | "fetching-ticket"
  | "connecting"
  | "reconnecting"
  | "awaiting-ready"
  | "active"
  | "closed"
  | "error";

export type CollaborationErrorCategory =
  | "ticket"
  | "transport"
  | "protocol"
  | "operation-rejected"
  | "resync-required"
  | "server"
  | "backpressure";

export interface CollaborationError {
  category: CollaborationErrorCategory;
  code: string;
  message: string;
  fatal: boolean;
  reconnectable: boolean;
  requiresAuthentication: boolean;
  requiresResync: boolean;
}

export interface PendingClientOperation extends IdentifiedOperation {
  baseRevision: number;
  operation: TextOperation;
  sent: boolean;
}

export interface CollaborationSnapshot {
  status: CollaborationStatus;
  documentId: string;
  syncEpoch: string;
  clientId: string;
  confirmedRevision: number;
  confirmedContent: string;
  optimisticContent: string;
  inFlight: PendingClientOperation | null;
  pendingBuffer: PendingClientOperation[];
  connectionId: string | null;
  role: "OWNER" | "EDITOR" | null;
  error: CollaborationError | null;
}

export interface ReconnectPolicy {
  initialDelayMs?: number;
  maxDelayMs?: number;
  multiplier?: number;
  maxRetries?: number;
}

export interface CollaborationClientOptions {
  documentId: string;
  syncEpoch: string;
  revision: number;
  content: string;
  clientId: string;
  ticketProvider: RealtimeTicketProvider;
  createTransport: () => RealtimeTransport;
  createUuid?: UuidFactory;
  now?: () => string;
  buildWebSocketUrl?: (websocketPath: string, ticket: string) => string;
  maxPendingOperations?: number;
  autoReconnect?: boolean;
  reconnectPolicy?: ReconnectPolicy;
  scheduleReconnect?: (callback: () => void, delayMs: number) => unknown;
  cancelScheduledReconnect?: (handle: unknown) => void;
}

type Listener = () => void;

export class CollaborationClient {
  private readonly listeners = new Set<Listener>();
  private readonly createUuid: UuidFactory;
  private readonly now: () => string;
  private readonly buildWebSocketUrl: (websocketPath: string, ticket: string) => string;
  private readonly maxPendingOperations: number;
  private readonly autoReconnect: boolean;
  private readonly reconnectPolicy: Required<ReconnectPolicy>;
  private readonly scheduleReconnect: (callback: () => void, delayMs: number) => unknown;
  private readonly cancelScheduledReconnect: (handle: unknown) => void;

  private transport: RealtimeTransport | null = null;
  private lifecycle = 0;
  private intentionallyStopped = false;
  private reconnectAttempt = 0;
  private reconnectTimer: unknown = null;
  private snapshot: CollaborationSnapshot;

  constructor(private readonly options: CollaborationClientOptions) {
    if (!Number.isSafeInteger(options.revision) || options.revision < 0) {
      throw new Error("Initial confirmed revision must be a non-negative safe integer.");
    }
    this.createUuid = options.createUuid ?? (() => crypto.randomUUID());
    this.now = options.now ?? (() => new Date().toISOString());
    this.buildWebSocketUrl = options.buildWebSocketUrl ?? buildRealtimeWebSocketUrl;
    this.maxPendingOperations = options.maxPendingOperations ?? 100;
    this.autoReconnect = options.autoReconnect ?? true;
    this.reconnectPolicy = {
      initialDelayMs: options.reconnectPolicy?.initialDelayMs ?? 200,
      maxDelayMs: options.reconnectPolicy?.maxDelayMs ?? 5000,
      multiplier: options.reconnectPolicy?.multiplier ?? 1.5,
      maxRetries: options.reconnectPolicy?.maxRetries ?? Infinity,
    };
    this.scheduleReconnect = options.scheduleReconnect ?? ((cb, ms) => setTimeout(cb, ms));
    this.cancelScheduledReconnect = options.cancelScheduledReconnect ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));

    this.snapshot = {
      status: "disconnected",
      documentId: options.documentId,
      syncEpoch: options.syncEpoch,
      clientId: options.clientId,
      confirmedRevision: options.revision,
      confirmedContent: options.content,
      optimisticContent: options.content,
      inFlight: null,
      pendingBuffer: [],
      connectionId: null,
      role: null,
      error: null,
    };
  }

  getSnapshot = (): CollaborationSnapshot => this.snapshot;

  subscribe = (listener: Listener): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  async start(): Promise<void> {
    if (this.snapshot.status !== "disconnected" && this.snapshot.status !== "reconnecting") {
      return;
    }

    this.reconnectAttempt = 0;
    this.clearReconnectTimer();
    this.intentionallyStopped = false;
    return this.connectAttempt(++this.lifecycle);
  }

  stop(): void {
    ++this.lifecycle;
    this.intentionallyStopped = true;
    this.clearReconnectTimer();
    this.transport?.close(1000, "Client stopped");
    this.transport = null;
    this.update({
      status: "disconnected",
      connectionId: null,
      role: null,
    });
  }

  submitLocalOperation(operation: PrimitiveOperation): string {
    if (this.snapshot.status === "closed" || (this.snapshot.status === "error" && this.snapshot.error?.fatal)) {
      throw new Error("Local operations are paused because realtime collaboration is unavailable.");
    }
    const pendingCount = (this.snapshot.inFlight === null ? 0 : 1)
      + this.snapshot.pendingBuffer.length;
    if (pendingCount >= this.maxPendingOperations) {
      const error: CollaborationError = {
        category: "backpressure",
        code: "PENDING_LIMIT_REACHED",
        message: "Local editing is paused because too many operations are awaiting confirmation.",
        fatal: false,
        reconnectable: false,
        requiresAuthentication: false,
        requiresResync: false,
      };
      this.fail(error);
      throw new Error(error.message);
    }

    assertOperationValidForDocument(this.snapshot.optimisticContent, operation);
    const pending: PendingClientOperation = {
      clientId: this.snapshot.clientId,
      clientOperationId: this.createUuid(),
      baseRevision: this.snapshot.confirmedRevision,
      operation,
      sent: false,
    };
    const optimisticContent = applyOperation(this.snapshot.optimisticContent, operation);

    if (this.snapshot.inFlight === null) {
      this.update({ inFlight: pending, optimisticContent });
    } else {
      this.update({
        pendingBuffer: [...this.snapshot.pendingBuffer, pending],
        optimisticContent,
      });
    }
    this.flushPendingOperation();
    return pending.clientOperationId;
  }

  private async connectAttempt(lifecycle: number): Promise<void> {
    if (lifecycle !== this.lifecycle || this.intentionallyStopped) {
      return;
    }
    this.update({ status: this.snapshot.status === "reconnecting" ? "reconnecting" : "fetching-ticket", error: null });

    let ticket;
    try {
      ticket = await this.options.ticketProvider.create(this.snapshot.documentId);
    } catch (error) {
      if (lifecycle !== this.lifecycle || this.intentionallyStopped) {
        return;
      }
      const fatal = !this.autoReconnect || isAuthenticationFailure(error) || isNotFoundFailure(error);
      const collabError: CollaborationError = {
        category: "ticket",
        code: errorCode(error, "TICKET_REQUEST_FAILED"),
        message: errorMessage(error, "Unable to create a realtime connection ticket."),
        fatal,
        reconnectable: !fatal,
        requiresAuthentication: isAuthenticationFailure(error),
        requiresResync: false,
      };
      if (fatal) {
        this.fail(collabError);
      } else {
        this.scheduleNextReconnect(lifecycle, collabError);
      }
      return;
    }

    if (lifecycle !== this.lifecycle || this.intentionallyStopped) {
      return;
    }

    try {
      const transport = this.options.createTransport();
      this.transport = transport;
      this.update({ status: "connecting" });
      transport.connect(
        this.buildWebSocketUrl(ticket.websocketPath, ticket.ticket),
        this.transportHandlers(lifecycle),
      );
    } catch (error) {
      if (lifecycle !== this.lifecycle || this.intentionallyStopped) {
        return;
      }
      const fatal = !this.autoReconnect;
      const collabError: CollaborationError = {
        category: "transport",
        code: errorCode(error, "WEBSOCKET_CONNECT_FAILED"),
        message: errorMessage(error, "Unable to open the realtime connection."),
        fatal,
        reconnectable: !fatal,
        requiresAuthentication: false,
        requiresResync: false,
      };
      if (fatal) {
        this.fail(collabError);
      } else {
        this.scheduleNextReconnect(lifecycle, collabError);
      }
    }
  }

  private transportHandlers(lifecycle: number): RealtimeTransportHandlers {
    return {
      onOpen: () => {
        if (lifecycle !== this.lifecycle || this.intentionallyStopped) {
          return;
        }
        this.update({ status: "awaiting-ready" });
        this.sendHello();
      },
      onMessage: (data) => {
        if (lifecycle !== this.lifecycle || this.intentionallyStopped) {
          return;
        }
        this.receive(data);
      },
      onError: () => {
        if (lifecycle !== this.lifecycle || this.snapshot.status === "error" || this.intentionallyStopped) {
          return;
        }
        const retryable = this.autoReconnect;
        const error: CollaborationError = {
          category: "transport",
          code: "WEBSOCKET_ERROR",
          message: "The realtime connection encountered a transport error.",
          fatal: !retryable,
          reconnectable: retryable,
          requiresAuthentication: false,
          requiresResync: false,
        };
        if (retryable) {
          this.scheduleNextReconnect(lifecycle, error);
        } else {
          this.fail(error);
        }
      },
      onClose: (event) => {
        if (lifecycle !== this.lifecycle || this.intentionallyStopped) {
          return;
        }
        this.handleClose(event.code, event.reason, event.wasClean, lifecycle);
      },
    };
  }

  private sendHello(): void {
    this.sendMessage(createClientHello({
      messageId: this.createUuid(),
      documentId: this.snapshot.documentId,
      syncEpoch: this.snapshot.syncEpoch,
      clientId: this.snapshot.clientId,
      timestamp: this.now(),
      knownRevision: this.snapshot.confirmedRevision,
    }));
  }

  private receive(data: unknown): void {
    if (typeof data !== "string") {
      this.protocolFailure("Server sent a non-text WebSocket frame.");
      return;
    }

    try {
      const message = parseServerMessage(data);
      if (message.documentId !== this.snapshot.documentId) {
        throw new RealtimeProtocolError("Server message documentId does not match this room.");
      }
      if ("syncEpoch" in message
        && message.syncEpoch !== undefined
        && message.syncEpoch !== this.snapshot.syncEpoch) {
        this.resyncFailure("EPOCH_MISMATCH", "Server message belongs to a different synchronization epoch.");
        return;
      }

      switch (message.type) {
        case "server.ready":
          this.handleReady(message);
          break;
        case "server.operations":
          this.handleOperations(message);
          break;
        case "server.operation_rejected":
          this.handleOperationRejected(message);
          break;
        case "server.resync_required":
          this.handleResyncRequired(message);
          break;
        case "server.error":
          this.handleServerError(message);
          break;
        case "presence.snapshot":
        case "presence.changed":
        case "cursor.remote":
          // Presence/cursor rendering is intentionally deferred to PRES-001.
          break;
      }
    } catch (error) {
      this.protocolFailure(errorMessage(error, "Malformed realtime server message."));
    }
  }

  private handleReady(message: ServerReadyMessage): void {
    if (this.snapshot.status !== "awaiting-ready") {
      throw new RealtimeProtocolError("server.ready arrived outside the bootstrap state.");
    }
    if (message.payload.revision !== this.snapshot.confirmedRevision) {
      this.resyncFailure(
        "PROTOCOL_ERROR",
        `server.ready revision ${message.payload.revision} does not match caught-up revision ${this.snapshot.confirmedRevision}.`,
      );
      return;
    }
    this.update({
      status: "active",
      connectionId: message.payload.connectionId,
      role: message.payload.role,
      error: null,
    });
    this.flushPendingOperation();
  }

  private handleOperations(message: ServerOperationsMessage): void {
    if (this.snapshot.status !== "awaiting-ready" && this.snapshot.status !== "active") {
      throw new RealtimeProtocolError("server.operations arrived before client.hello or after closure.");
    }

    let confirmedRevision = this.snapshot.confirmedRevision;
    let confirmedContent = this.snapshot.confirmedContent;
    let optimisticContent = this.snapshot.optimisticContent;
    let inFlight = this.snapshot.inFlight;
    let pendingBuffer = [...this.snapshot.pendingBuffer];
    let gapError: CollaborationError | null = null;

    for (const canonical of message.payload.operations) {
      const acknowledgementMatches = matchesInFlight(canonical, inFlight, this.snapshot.clientId);

      if (canonical.revision > confirmedRevision + 1) {
        gapError = {
          category: "resync-required",
          code: "REVISION_GAP",
          message: `Expected canonical revision ${confirmedRevision + 1} but received ${canonical.revision}.`,
          fatal: true,
          reconnectable: false,
          requiresAuthentication: false,
          requiresResync: true,
        };
      } else if (canonical.revision === confirmedRevision + 1) {
        if (acknowledgementMatches) {
          confirmedContent = applyOperation(confirmedContent, canonical.operation);
        } else {
          const remote = identifiedCanonical(canonical);
          const rebased = rebasePendingOperations(remote, inFlight, pendingBuffer);
          confirmedContent = applyOperation(confirmedContent, canonical.operation);
          optimisticContent = applyOperation(
            optimisticContent,
            rebased.remoteForOptimistic.operation,
          );
          inFlight = rebased.inFlight === null
            ? null
            : preservePendingMetadata(rebased.inFlight, inFlight!);
          pendingBuffer = rebased.pendingBuffer.map((operation, index) =>
            preservePendingMetadata(operation, pendingBuffer[index]));
        }
        confirmedRevision = canonical.revision;
      }

      // ACK matching remains independent from duplicate/gap revision processing.
      if (acknowledgementMatches) {
        inFlight = null;
        const promoted = promoteBuffered(pendingBuffer, confirmedRevision);
        inFlight = promoted.inFlight;
        pendingBuffer = promoted.pendingBuffer;
      }

      if (gapError !== null) {
        break;
      }
    }

    this.snapshot = {
      ...this.snapshot,
      confirmedRevision,
      confirmedContent,
      optimisticContent,
      inFlight,
      pendingBuffer,
      ...(gapError === null ? {} : { status: "error", error: gapError }),
    };
    this.emit();
    if (gapError === null) {
      this.flushPendingOperation();
    }
  }

  private handleOperationRejected(message: ServerOperationRejectedMessage): void {
    const matchesCurrent = this.snapshot.inFlight?.clientOperationId
      === message.payload.clientOperationId;
    const error: CollaborationError = {
      category: "operation-rejected",
      code: message.payload.code,
      message: message.payload.message,
      fatal: matchesCurrent,
      reconnectable: false,
      requiresAuthentication: false,
      requiresResync: false,
    };
    if (matchesCurrent) {
      // The local text is intentionally preserved for explicit user recovery.
      this.fail(error);
    } else {
      this.update({ error });
    }
  }

  private handleResyncRequired(message: ServerResyncRequiredMessage): void {
    this.resyncFailure(
      message.payload.reason,
      `The server requires document resynchronization: ${message.payload.reason}.`,
    );
  }

  private handleServerError(message: ServerErrorMessage): void {
    const error: CollaborationError = {
      category: "server",
      code: message.payload.code,
      message: message.payload.message,
      fatal: message.payload.fatal,
      reconnectable: message.payload.code === "INTERNAL_ERROR",
      requiresAuthentication: message.payload.closeCode === 4001,
      requiresResync: false,
    };
    if (message.payload.fatal) {
      this.fail(error);
    } else {
      this.update({ error });
    }
  }

  private flushPendingOperation(): void {
    if (this.snapshot.status !== "active" || this.snapshot.inFlight === null
      || this.snapshot.inFlight.sent) {
      return;
    }

    const operation = this.snapshot.inFlight.operation;
    const primitives = flattenOperation(operation);

    if (primitives.length === 0) {
      const promoted = promoteBuffered(
        this.snapshot.pendingBuffer,
        this.snapshot.confirmedRevision,
      );
      this.update({ inFlight: promoted.inFlight, pendingBuffer: promoted.pendingBuffer });
      this.flushPendingOperation();
      return;
    }

    if (primitives.length === 1) {
      const inFlight: PendingClientOperation = {
        ...this.snapshot.inFlight,
        operation: primitives[0],
        baseRevision: this.snapshot.confirmedRevision,
        sent: true,
      };
      this.update({ inFlight });
      this.sendMessage(createClientOperation({
        messageId: this.createUuid(),
        documentId: this.snapshot.documentId,
        syncEpoch: this.snapshot.syncEpoch,
        clientId: this.snapshot.clientId,
        timestamp: this.now(),
        clientOperationId: inFlight.clientOperationId,
        baseRevision: inFlight.baseRevision,
        operation: primitives[0],
      }));
      return;
    }

    // Multiple primitive operations resulting from local GROUP decomposition:
    // First primitive retains inFlight identity; remaining primitives are prepended to pendingBuffer.
    const firstOp = primitives[0];
    const remainingOps: PendingClientOperation[] = primitives.slice(1).map((op) => ({
      clientId: this.snapshot.clientId,
      clientOperationId: this.createUuid(),
      baseRevision: this.snapshot.confirmedRevision,
      operation: op,
      sent: false,
    }));

    const inFlight: PendingClientOperation = {
      ...this.snapshot.inFlight,
      operation: firstOp,
      baseRevision: this.snapshot.confirmedRevision,
      sent: true,
    };

    this.update({
      inFlight,
      pendingBuffer: [...remainingOps, ...this.snapshot.pendingBuffer],
    });

    this.sendMessage(createClientOperation({
      messageId: this.createUuid(),
      documentId: this.snapshot.documentId,
      syncEpoch: this.snapshot.syncEpoch,
      clientId: this.snapshot.clientId,
      timestamp: this.now(),
      clientOperationId: inFlight.clientOperationId,
      baseRevision: inFlight.baseRevision,
      operation: firstOp,
    }));
  }

  private sendMessage(message: unknown): void {
    try {
      this.transport?.send(JSON.stringify(message));
    } catch (error) {
      this.fail({
        category: "transport",
        code: "SEND_FAILED",
        message: errorMessage(error, "Unable to send a realtime message."),
        fatal: true,
        reconnectable: true,
        requiresAuthentication: false,
        requiresResync: false,
      });
    }
  }

  private protocolFailure(message: string): void {
    this.fail({
      category: "protocol",
      code: "INVALID_SERVER_MESSAGE",
      message,
      fatal: true,
      reconnectable: false,
      requiresAuthentication: false,
      requiresResync: true,
    });
    this.transport?.close(4000, "Invalid server message");
  }

  private resyncFailure(code: string, message: string): void {
    this.fail({
      category: "resync-required",
      code,
      message,
      fatal: true,
      reconnectable: false,
      requiresAuthentication: false,
      requiresResync: true,
    });
  }

  private handleClose(code: number, reason: string, wasClean: boolean, lifecycle: number): void {
    this.transport = null;
    if (this.intentionallyStopped) {
      return;
    }
    // Preserve a structured fatal server/protocol error when its expected close frame follows.
    if (this.snapshot.status === "error" && this.snapshot.error?.fatal) {
      return;
    }
    if (code === 1000) {
      this.update({ status: "closed", connectionId: null, role: null });
      return;
    }

    const retryable = (code === 1001 || code === 1006 || code === 1008 || code === 1011) && this.autoReconnect;

    const error: CollaborationError = {
      category: "transport",
      code: `WEBSOCKET_CLOSED_${code}`,
      message: reason || `Realtime connection closed${wasClean ? "" : " unexpectedly"} (${code}).`,
      fatal: !retryable,
      reconnectable: retryable,
      requiresAuthentication: code === 4001,
      requiresResync: false,
    };

    if (retryable) {
      this.scheduleNextReconnect(lifecycle, error);
    } else {
      this.fail(error);
    }
  }

  private scheduleNextReconnect(lifecycle: number, error?: CollaborationError): void {
    if (this.intentionallyStopped || !this.autoReconnect) {
      return;
    }
    this.clearReconnectTimer();
    this.transport = null;

    // Reset sent flag on in-flight operation so it will be retried after reconnect if unconfirmed
    if (this.snapshot.inFlight !== null) {
      this.snapshot = {
        ...this.snapshot,
        inFlight: {
          ...this.snapshot.inFlight,
          sent: false,
        },
      };
    }

    const delay = Math.min(
      this.reconnectPolicy.initialDelayMs * Math.pow(this.reconnectPolicy.multiplier, this.reconnectAttempt),
      this.reconnectPolicy.maxDelayMs,
    );
    this.reconnectAttempt++;

    this.update({
      status: "reconnecting",
      connectionId: null,
      role: null,
      ...(error ? { error } : {}),
    });

    this.reconnectTimer = this.scheduleReconnect(
      () => {
        this.reconnectTimer = null;
        if (lifecycle === this.lifecycle && !this.intentionallyStopped) {
          void this.connectAttempt(lifecycle);
        }
      },
      delay,
    );
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      this.cancelScheduledReconnect(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private fail(error: CollaborationError): void {
    this.clearReconnectTimer();
    this.update({ status: "error", error, connectionId: null, role: null });
  }

  private update(changes: Partial<CollaborationSnapshot>): void {
    this.snapshot = { ...this.snapshot, ...changes };
    this.emit();
  }

  private emit(): void {
    this.listeners.forEach((listener) => listener());
  }
}

function identifiedCanonical(canonical: CanonicalOperationItem): IdentifiedOperation {
  return {
    clientId: canonical.clientId,
    clientOperationId: canonical.clientOperationId,
    baseRevision: canonical.revision - 1,
    operation: canonical.operation,
  };
}

function matchesInFlight(
  canonical: CanonicalOperationItem,
  inFlight: PendingClientOperation | null,
  localClientId: string,
): boolean {
  return canonical.clientId === localClientId
    && inFlight !== null
    && canonical.clientOperationId === inFlight.clientOperationId;
}

function preservePendingMetadata(
  operation: IdentifiedOperation,
  previous: PendingClientOperation,
): PendingClientOperation {
  return {
    ...operation,
    baseRevision: previous.baseRevision,
    sent: previous.sent,
  };
}

function promoteBuffered(
  pendingBuffer: PendingClientOperation[],
  confirmedRevision: number,
): { inFlight: PendingClientOperation | null; pendingBuffer: PendingClientOperation[] } {
  if (pendingBuffer.length === 0) {
    return { inFlight: null, pendingBuffer: [] };
  }
  const [head, ...rest] = pendingBuffer;
  return {
    inFlight: {
      ...head,
      baseRevision: confirmedRevision,
      sent: false,
    },
    pendingBuffer: rest,
  };
}

function flattenOperation(operation: TextOperation): PrimitiveOperation[] {
  if (operation.kind === "NO_OP") {
    return [];
  }
  if (operation.kind === "INSERT" || operation.kind === "DELETE") {
    return [operation];
  }
  if (operation.kind === "GROUP") {
    return operation.operations.flatMap(flattenOperation);
  }
  return [];
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function errorCode(error: unknown, fallback: string): string {
  if (typeof error === "object" && error !== null && "code" in error
    && typeof error.code === "string") {
    return error.code;
  }
  return fallback;
}

function isAuthenticationFailure(error: unknown): boolean {
  return typeof error === "object" && error !== null
    && (("status" in error && error.status === 401)
      || ("code" in error && error.code === "UNAUTHENTICATED"));
}

function isNotFoundFailure(error: unknown): boolean {
  return typeof error === "object" && error !== null
    && (("status" in error && error.status === 404)
      || ("code" in error && error.code === "DOCUMENT_NOT_FOUND"));
}
