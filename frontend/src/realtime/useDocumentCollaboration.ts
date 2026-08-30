import { useEffect, useMemo } from "react";
import type { DocumentDetail } from "../documents/types";
import { tabClientId } from "./clientId";
import { CollaborationClient } from "./CollaborationClient";
import { realtimeTicketApi } from "./ticketApi";
import { BrowserWebSocketTransport } from "./transport";

export function useDocumentCollaboration(
  document: DocumentDetail,
  accessToken: string,
): CollaborationClient {
  const client = useMemo(() => new CollaborationClient({
    documentId: document.id,
    syncEpoch: document.syncEpoch,
    revision: document.currentRevision,
    content: document.content,
    clientId: tabClientId.getClientId(),
    ticketProvider: realtimeTicketApi(accessToken),
    createTransport: () => new BrowserWebSocketTransport(),
  }), [accessToken, document.content, document.currentRevision, document.id, document.syncEpoch]);

  useEffect(() => {
    void client.start();
    return () => client.stop();
  }, [client]);

  return client;
}
