import { apiRequest } from "../auth/api";
import type {
  CreateDocumentInput,
  DocumentDetail,
  DocumentPage,
  DocumentPermissionEntry,
  DocumentPermissions,
} from "./types";

export function documentApi(accessToken: string) {
  const authorizedRequest = <T>(path: string, init: RequestInit = {}) => apiRequest<T>(path, init, accessToken);

  return {
    list(cursor?: string): Promise<DocumentPage> {
      const query = new URLSearchParams({ limit: "20" });
      if (cursor !== undefined) {
        query.set("cursor", cursor);
      }
      return authorizedRequest<DocumentPage>(`/documents?${query.toString()}`);
    },
    create(input: CreateDocumentInput): Promise<DocumentDetail> {
      return authorizedRequest<DocumentDetail>("/documents", {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
    get(documentId: string): Promise<DocumentDetail> {
      return authorizedRequest<DocumentDetail>(`/documents/${encodeURIComponent(documentId)}`);
    },
    rename(documentId: string, title: string): Promise<DocumentDetail> {
      return authorizedRequest<DocumentDetail>(`/documents/${encodeURIComponent(documentId)}`, {
        method: "PATCH",
        body: JSON.stringify({ title }),
      });
    },
    delete(documentId: string): Promise<void> {
      return authorizedRequest<void>(`/documents/${encodeURIComponent(documentId)}`, { method: "DELETE" });
    },
    permissions(documentId: string): Promise<DocumentPermissions> {
      return authorizedRequest<DocumentPermissions>(`/documents/${encodeURIComponent(documentId)}/permissions`);
    },
    grant(documentId: string, userIdentifier: string): Promise<DocumentPermissionEntry> {
      return authorizedRequest<DocumentPermissionEntry>(`/documents/${encodeURIComponent(documentId)}/permissions`, {
        method: "POST",
        body: JSON.stringify({ userIdentifier, role: "EDITOR" }),
      });
    },
    revoke(documentId: string, userId: string): Promise<void> {
      return authorizedRequest<void>(
        `/documents/${encodeURIComponent(documentId)}/permissions/${encodeURIComponent(userId)}`,
        { method: "DELETE" },
      );
    },
  };
}
