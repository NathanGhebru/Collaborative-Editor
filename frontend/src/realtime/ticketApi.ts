import { apiRequest } from "../auth/api";
import { parseRealtimeTicket, type RealtimeTicket } from "./protocol";

export interface RealtimeTicketProvider {
  create(documentId: string): Promise<RealtimeTicket>;
}

export function realtimeTicketApi(accessToken: string): RealtimeTicketProvider {
  return {
    async create(documentId: string): Promise<RealtimeTicket> {
      const response = await apiRequest<unknown>(
        `/documents/${encodeURIComponent(documentId)}/realtime-ticket`,
        { method: "POST" },
        accessToken,
      );
      return parseRealtimeTicket(response);
    },
  };
}
