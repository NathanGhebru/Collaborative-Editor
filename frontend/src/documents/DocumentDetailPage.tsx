import { type FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { ApiError } from "../auth/api";
import { useAuth } from "../auth/AuthProvider";
import { documentApi } from "./api";
import type { DocumentDetail, DocumentPermissionEntry } from "./types";

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return "Unable to complete the document request. Please try again.";
}

function returnToDocuments() {
  window.location.hash = "#/documents";
}

export function DocumentDetailPage({ documentId }: { documentId: string }) {
  const { accessToken } = useAuth();
  const api = useMemo(() => (accessToken === null ? null : documentApi(accessToken)), [accessToken]);
  const [document, setDocument] = useState<DocumentDetail | null>(null);
  const [title, setTitle] = useState("");
  const [permissions, setPermissions] = useState<DocumentPermissionEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingPermissions, setLoadingPermissions] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [savingTitle, setSavingTitle] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [newEditor, setNewEditor] = useState("");
  const [sharing, setSharing] = useState(false);
  const [revokingUserId, setRevokingUserId] = useState<string | null>(null);

  const loadPermissions = useCallback(async () => {
    if (api === null) {
      return;
    }
    setLoadingPermissions(true);
    try {
      const response = await api.permissions(documentId);
      setPermissions(response.permissions);
    } catch (requestError) {
      setActionError(messageFor(requestError));
    } finally {
      setLoadingPermissions(false);
    }
  }, [api, documentId]);

  const loadDocument = useCallback(async () => {
    if (api === null) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const response = await api.get(documentId);
      setDocument(response);
      setTitle(response.title);
      if (response.permission === "OWNER") {
        await loadPermissions();
      }
    } catch (requestError) {
      setError(messageFor(requestError));
    } finally {
      setLoading(false);
    }
  }, [api, documentId, loadPermissions]);

  useEffect(() => {
    void loadDocument();
  }, [loadDocument]);

  async function saveTitle(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedTitle = title.trim();
    if (!normalizedTitle || normalizedTitle.length > 255 || /[\r\n]/.test(normalizedTitle)) {
      setActionError("Title must be 1 to 255 characters and cannot contain a newline.");
      return;
    }
    if (api === null) {
      return;
    }
    setSavingTitle(true);
    setActionError(null);
    try {
      const updated = await api.rename(documentId, normalizedTitle);
      setDocument((current) => (current === null ? current : { ...current, ...updated, content: current.content }));
      setTitle(updated.title);
    } catch (requestError) {
      setActionError(messageFor(requestError));
    } finally {
      setSavingTitle(false);
    }
  }

  async function deleteDocument() {
    if (api === null) {
      return;
    }
    setDeleting(true);
    setActionError(null);
    try {
      await api.delete(documentId);
      returnToDocuments();
    } catch (requestError) {
      setActionError(messageFor(requestError));
    } finally {
      setDeleting(false);
    }
  }

  async function grantEditor(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const userIdentifier = newEditor.trim();
    if (!userIdentifier) {
      setActionError("Enter a username or email address.");
      return;
    }
    if (api === null) {
      return;
    }
    setSharing(true);
    setActionError(null);
    try {
      const permission = await api.grant(documentId, userIdentifier);
      setPermissions((current) => [...current, permission]);
      setNewEditor("");
    } catch (requestError) {
      setActionError(messageFor(requestError));
    } finally {
      setSharing(false);
    }
  }

  async function revokeEditor(userId: string) {
    if (api === null) {
      return;
    }
    setRevokingUserId(userId);
    setActionError(null);
    try {
      await api.revoke(documentId, userId);
      setPermissions((current) => current.filter((permission) => permission.user.id !== userId));
    } catch (requestError) {
      setActionError(messageFor(requestError));
    } finally {
      setRevokingUserId(null);
    }
  }

  if (loading) {
    return <main><p role="status">Loading document...</p></main>;
  }

  if (error !== null || document === null) {
    return (
      <main>
        <section className="document-card" aria-labelledby="document-error-heading">
          <h1 id="document-error-heading">Document unavailable</h1>
          <p role="alert">{error ?? "The requested document could not be loaded."}</p>
          <button type="button" onClick={returnToDocuments}>Return to documents</button>
        </section>
      </main>
    );
  }

  const isOwner = document.permission === "OWNER";
  return (
    <main className="document-page">
      <button type="button" className="back-link" onClick={returnToDocuments}>Back to documents</button>
      <section className="document-card" aria-labelledby="document-heading">
        <p className="eyebrow">{isOwner ? "Owner" : "Editor"}</p>
        <h1 id="document-heading">{document.title}</h1>
        <p>Owned by {document.owner.displayName}. {isOwner ? "You control access and deletion." : "You can rename this document."}</p>
        {actionError !== null && <p className="form-error" role="alert">{actionError}</p>}
        <form onSubmit={saveTitle} noValidate>
          <label htmlFor="rename-document">Document title</label>
          <input id="rename-document" value={title} onChange={(event) => setTitle(event.target.value)} />
          <button type="submit" disabled={savingTitle}>{savingTitle ? "Saving..." : "Save title"}</button>
        </form>
      </section>

      <section className="document-card" aria-labelledby="document-content-heading">
        <h2 id="document-content-heading">Current document text</h2>
        <pre className="document-content">{document.content || "This document is empty."}</pre>
        <p className="muted">Live text editing is introduced in the next editor phase.</p>
      </section>

      {isOwner && (
        <section className="document-card" aria-labelledby="sharing-heading">
          <h2 id="sharing-heading">Sharing</h2>
          <form onSubmit={grantEditor} noValidate>
            <label htmlFor="editor-identifier">Username or email</label>
            <input id="editor-identifier" value={newEditor} onChange={(event) => setNewEditor(event.target.value)} />
            <button type="submit" disabled={sharing}>{sharing ? "Granting access..." : "Grant editor access"}</button>
          </form>
          {loadingPermissions ? <p role="status">Loading collaborators...</p> : (
            <ul className="permissions-list">
              {permissions.map((permission) => (
                <li key={permission.user.id}>
                  <span>{permission.user.displayName} (@{permission.user.username}) — Editor</span>
                  <button type="button" onClick={() => void revokeEditor(permission.user.id)} disabled={revokingUserId === permission.user.id}>
                    {revokingUserId === permission.user.id ? "Revoking..." : "Revoke access"}
                  </button>
                </li>
              ))}
              {permissions.length === 0 && <li>No editors have access yet.</li>}
            </ul>
          )}
        </section>
      )}

      {isOwner && (
        <section className="danger-zone" aria-labelledby="delete-heading">
          <h2 id="delete-heading">Delete document</h2>
          <p>Deletion permanently removes this document and its shared access.</p>
          <button type="button" onClick={() => void deleteDocument()} disabled={deleting}>
            {deleting ? "Deleting..." : "Delete document"}
          </button>
        </section>
      )}
    </main>
  );
}
