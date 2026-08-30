import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App";

const user = { id: "user-1", username: "nathan", displayName: "Nathan", createdAt: "2026-08-28T07:00:00Z" };
const owner = user;
const collaborator = { id: "user-2", username: "collaborator", displayName: "Collaborator" };
const firstDocument = {
  id: "document-1", title: "Distributed Systems Notes", owner, permission: "OWNER" as const,
  currentRevision: 0, syncEpoch: "epoch-1", createdAt: "2026-08-28T07:00:00Z", updatedAt: "2026-08-28T07:00:00Z",
};
const secondDocument = {
  ...firstDocument, id: "document-2", title: "Shared Notes", permission: "EDITOR" as const,
};

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

function authenticated(fetchMock: ReturnType<typeof vi.fn>) {
  fetchMock
    .mockResolvedValueOnce(response(200, { accessToken: "access-token", expiresInSeconds: 900 }))
    .mockResolvedValueOnce(response(200, { ...user, email: "nathan@example.com" }));
}

describe("document management flows", () => {
  beforeEach(() => {
    window.location.hash = "#/documents";
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("lists accessible documents and appends the next cursor page", async () => {
    const fetchMock = vi.fn();
    authenticated(fetchMock);
    fetchMock
      .mockResolvedValueOnce(response(200, { documents: [firstDocument, secondDocument], nextCursor: "next-page" }))
      .mockResolvedValueOnce(response(200, { documents: [{ ...firstDocument, id: "document-3", title: "Later document" }], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByText("Distributed Systems Notes")).toBeInTheDocument();
    const listRequest = fetchMock.mock.calls[2][1] as RequestInit;
    expect(new Headers(listRequest.headers).get("Authorization")).toBe("Bearer access-token");
    expect(screen.getByText("Editor · owned by Nathan")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Load more documents" }));

    expect(await screen.findByText("Later document")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenLastCalledWith(
      "/api/v1/documents?limit=20&cursor=next-page",
      expect.objectContaining({ credentials: "include" }),
    );
  });

  it("shows an empty state and creates a document with the frozen request shape", async () => {
    const detail = { ...firstDocument, content: "Initial text" };
    const fetchMock = vi.fn();
    authenticated(fetchMock);
    fetchMock
      .mockResolvedValueOnce(response(200, { documents: [], nextCursor: null }))
      .mockResolvedValueOnce(response(201, detail))
      .mockResolvedValueOnce(response(200, detail))
      .mockResolvedValueOnce(response(200, { owner, permissions: [] }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByText("No documents yet. Create your first document to get started.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Title"), { target: { value: " Distributed Systems Notes " } });
    fireEvent.change(screen.getByLabelText("Initial text (optional)"), { target: { value: "Initial text" } });
    fireEvent.click(screen.getByRole("button", { name: "Create document" }));

    expect(await screen.findByRole("heading", { name: "Distributed Systems Notes" })).toBeInTheDocument();
    expect(fetchMock.mock.calls[3]).toEqual([
      "/api/v1/documents",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ title: "Distributed Systems Notes", initialContent: "Initial text" }),
      }),
    ]);
  });

  it("opens an owner document, renames it, shares it, revokes access, and deletes it", async () => {
    window.location.hash = "#/documents/document-1";
    const detail = { ...firstDocument, content: "Snapshot text" };
    const renamed = { ...detail, title: "Renamed notes" };
    const permission = { user: collaborator, role: "EDITOR" as const, createdAt: "2026-08-28T08:15:00Z" };
    const fetchMock = vi.fn();
    authenticated(fetchMock);
    fetchMock
      .mockResolvedValueOnce(response(200, detail))
      .mockResolvedValueOnce(response(200, { owner, permissions: [] }))
      .mockResolvedValueOnce(response(200, renamed))
      .mockResolvedValueOnce(response(201, permission))
      .mockResolvedValueOnce(response(204))
      .mockResolvedValueOnce(response(204))
      .mockResolvedValueOnce(response(200, { documents: [], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByText("Snapshot text")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Document title"), { target: { value: "Renamed notes" } });
    fireEvent.click(screen.getByRole("button", { name: "Save title" }));
    expect(await screen.findByRole("heading", { name: "Renamed notes" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Username or email"), { target: { value: " collaborator@example.com " } });
    fireEvent.click(screen.getByRole("button", { name: "Grant editor access" }));
    expect(await screen.findByText("Collaborator (@collaborator) — Editor")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Revoke access" }));
    expect(await screen.findByText("No editors have access yet.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Delete document" }));
    expect(await screen.findByRole("heading", { name: "Your workspace" })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/documents/document-1/permissions/user-2",
      expect.objectContaining({ method: "DELETE" }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/documents/document-1",
      expect.objectContaining({ method: "DELETE" }),
    );
  });

  it("shows editor behavior without owner-only sharing or deletion controls", async () => {
    window.location.hash = "#/documents/document-2";
    const editorDetail = { ...secondDocument, content: "Shared snapshot" };
    const fetchMock = vi.fn();
    authenticated(fetchMock);
    fetchMock
      .mockResolvedValueOnce(response(200, editorDetail))
      .mockResolvedValueOnce(response(200, { ...editorDetail, title: "Edited shared title" }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByText(/You can rename this document\./)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Sharing" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete document" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Document title"), { target: { value: "Edited shared title" } });
    fireEvent.click(screen.getByRole("button", { name: "Save title" }));
    expect(await screen.findByRole("heading", { name: "Edited shared title" })).toBeInTheDocument();
  });

  it("displays API failures for a denied direct document route", async () => {
    window.location.hash = "#/documents/private-document";
    const fetchMock = vi.fn();
    authenticated(fetchMock);
    fetchMock.mockResolvedValueOnce(response(404, { error: { code: "DOCUMENT_NOT_FOUND", message: "The requested document does not exist." } }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Document unavailable" })).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("The requested document does not exist.");
  });
});
