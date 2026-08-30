import { type FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { ApiError } from "../auth/api";
import { useAuth } from "../auth/AuthProvider";
import { documentApi } from "./api";
import type { DocumentSummary } from "./types";

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return "Unable to complete the document request. Please try again.";
}

function openDocument(documentId: string) {
  window.location.hash = `#/documents/${documentId}`;
}

export function DocumentsDashboard() {
  const { accessToken } = useAuth();
  const api = useMemo(() => (accessToken === null ? null : documentApi(accessToken)), [accessToken]);
  const [documents, setDocuments] = useState<DocumentSummary[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [initialContent, setInitialContent] = useState("");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const loadPage = useCallback(async (cursor?: string, append = false) => {
    if (api === null) {
      return;
    }
    if (append) {
      setLoadingMore(true);
    } else {
      setLoading(true);
    }
    setError(null);
    try {
      const page = await api.list(cursor);
      setDocuments((current) => (append ? [...current, ...page.documents] : page.documents));
      setNextCursor(page.nextCursor);
    } catch (requestError) {
      setError(messageFor(requestError));
    } finally {
      if (append) {
        setLoadingMore(false);
      } else {
        setLoading(false);
      }
    }
  }, [api]);

  useEffect(() => {
    void loadPage();
  }, [loadPage]);

  async function createDocument(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedTitle = title.trim();
    if (!normalizedTitle || normalizedTitle.length > 255 || /[\r\n]/.test(normalizedTitle)) {
      setCreateError("Title must be 1 to 255 characters and cannot contain a newline.");
      return;
    }
    if (initialContent.length > 1_000_000) {
      setCreateError("Initial content cannot exceed 1,000,000 characters.");
      return;
    }
    if (api === null) {
      return;
    }

    setCreating(true);
    setCreateError(null);
    try {
      const document = await api.create({
        title: normalizedTitle,
        ...(initialContent ? { initialContent } : {}),
      });
      openDocument(document.id);
    } catch (requestError) {
      setCreateError(messageFor(requestError));
    } finally {
      setCreating(false);
    }
  }

  if (loading) {
    return <main><p role="status">Loading your documents...</p></main>;
  }

  return (
    <main className="document-page">
      <header className="page-heading">
        <div>
          <p className="eyebrow">Documents</p>
          <h1>Your workspace</h1>
        </div>
      </header>

      <section className="document-card" aria-labelledby="new-document-heading">
        <h2 id="new-document-heading">Create a document</h2>
        {createError !== null && <p className="form-error" role="alert">{createError}</p>}
        <form onSubmit={createDocument} noValidate>
          <label htmlFor="document-title">Title</label>
          <input id="document-title" value={title} onChange={(event) => setTitle(event.target.value)} />
          <label htmlFor="initial-content">Initial text (optional)</label>
          <textarea id="initial-content" value={initialContent} onChange={(event) => setInitialContent(event.target.value)} />
          <button type="submit" disabled={creating}>{creating ? "Creating..." : "Create document"}</button>
        </form>
      </section>

      <section aria-labelledby="document-list-heading">
        <h2 id="document-list-heading">Accessible documents</h2>
        {error !== null && (
          <div className="inline-error" role="alert">
            <p>{error}</p>
            <button type="button" onClick={() => void loadPage()}>Retry</button>
          </div>
        )}
        {error === null && documents.length === 0 && <p className="empty-state">No documents yet. Create your first document to get started.</p>}
        <ul className="document-list">
          {documents.map((document) => (
            <li key={document.id}>
              <button type="button" className="document-link" onClick={() => openDocument(document.id)}>
                <span>{document.title}</span>
                <small>{document.permission === "OWNER" ? "Owner" : `Editor · owned by ${document.owner.displayName}`}</small>
              </button>
            </li>
          ))}
        </ul>
        {nextCursor !== null && (
          <button type="button" onClick={() => void loadPage(nextCursor, true)} disabled={loadingMore}>
            {loadingMore ? "Loading..." : "Load more documents"}
          </button>
        )}
      </section>
    </main>
  );
}
