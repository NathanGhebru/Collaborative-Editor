export type DocumentPermission = "OWNER" | "EDITOR";

export interface Collaborator {
  id: string;
  username: string;
  displayName: string;
}

export interface DocumentSummary {
  id: string;
  title: string;
  owner: Collaborator;
  permission: DocumentPermission;
  currentRevision: number;
  syncEpoch: string;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentDetail extends DocumentSummary {
  content: string;
}

export interface DocumentPage {
  documents: DocumentSummary[];
  nextCursor: string | null;
}

export interface DocumentPermissionEntry {
  user: Collaborator;
  role: "EDITOR";
  createdAt: string;
}

export interface DocumentPermissions {
  owner: Collaborator;
  permissions: DocumentPermissionEntry[];
}

export interface CreateDocumentInput {
  title: string;
  initialContent?: string;
}
