import { describe, expect, it } from "vitest";
import protocolFixtureJson from "../../../docs/realtime-protocol-fixtures.json";
import type { PrimitiveOperation } from "../ot/types";
import {
  createClientHello,
  createClientOperation,
  parseRealtimeTicket,
  parseServerMessage,
  RealtimeProtocolError,
} from "./protocol";

const fixtures = protocolFixtureJson.fixtures;

describe("frozen realtime protocol fixtures", () => {
  it("parses the canonical ticket and every server message fixture", () => {
    expect(parseRealtimeTicket(fixtures.realtimeTicketResponseSuccess.body)).toEqual(
      fixtures.realtimeTicketResponseSuccess.body,
    );

    for (const message of [
      fixtures.serverReady,
      fixtures.serverOperationsBroadcastInsert,
      fixtures.serverOperationsBroadcastSplitDeleteGroup,
      fixtures.serverOperationsBroadcastNoOp,
      fixtures.serverOperationRejected,
      fixtures.serverErrorFatalUnauthorized,
      fixtures.serverErrorNonFatalInvalidMessage,
      fixtures.serverResyncRequired,
    ]) {
      expect(parseServerMessage(JSON.stringify(message))).toEqual(message);
    }
  });

  it("creates client.hello with the exact frozen envelope", () => {
    const fixture = fixtures.clientHello;
    expect(createClientHello({
      messageId: fixture.messageId,
      documentId: fixture.documentId,
      syncEpoch: fixture.syncEpoch,
      clientId: fixture.clientId,
      timestamp: fixture.timestamp,
      knownRevision: fixture.payload.knownRevision,
    })).toEqual(fixture);
  });

  it("creates only primitive client.operation messages from the frozen fixtures", () => {
    for (const fixture of [fixtures.clientOperationInsert, fixtures.clientOperationDelete]) {
      expect(createClientOperation({
        messageId: fixture.messageId,
        documentId: fixture.documentId,
        syncEpoch: fixture.syncEpoch,
        clientId: fixture.clientId,
        timestamp: fixture.timestamp,
        clientOperationId: fixture.payload.clientOperationId,
        baseRevision: fixture.payload.baseRevision,
        operation: fixture.payload.operation as PrimitiveOperation,
      })).toEqual(fixture);
    }

    expect(() => createClientOperation({
      messageId: "message",
      documentId: "document",
      syncEpoch: "epoch",
      clientId: "client",
      timestamp: "2026-08-30T12:15:00Z",
      clientOperationId: "operation",
      baseRevision: 0,
      operation: { kind: "NO_OP" } as never,
    })).toThrow(RealtimeProtocolError);
  });

  it("rejects malformed JSON, missing fields, empty batches, and revision holes", () => {
    expect(() => parseServerMessage("not-json")).toThrow(RealtimeProtocolError);
    expect(() => parseServerMessage(JSON.stringify({
      ...fixtures.serverReady,
      protocolVersion: 2,
    }))).toThrow(/unsupported protocol version/i);
    expect(() => parseServerMessage(JSON.stringify({
      ...fixtures.serverReady,
      payload: { revision: 1 },
    }))).toThrow(/role|connectionId/i);
    expect(() => parseServerMessage(JSON.stringify({
      ...fixtures.serverOperationsBroadcastInsert,
      payload: { operations: [] },
    }))).toThrow(/non-empty/i);

    const first = fixtures.serverOperationsBroadcastInsert.payload.operations[0];
    expect(() => parseServerMessage(JSON.stringify({
      ...fixtures.serverOperationsBroadcastInsert,
      payload: {
        operations: [first, { ...first, revision: first.revision + 2 }],
      },
    }))).toThrow(/contiguous/i);
  });
});
