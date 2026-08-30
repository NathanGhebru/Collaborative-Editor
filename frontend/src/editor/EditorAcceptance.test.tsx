import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App";

const user = {
  id: "user-1",
  username: "nathan",
  displayName: "Nathan",
  createdAt: "2026-08-28T07:00:00Z",
};

const ownerDocument = {
  id: "document-1",
  title: "Unicode notes",
  content: "A😀B\nsecond line",
  owner: user,
  permission: "OWNER" as const,
  currentRevision: 0,
  syncEpoch: "epoch-1",
  createdAt: "2026-08-28T07:00:00Z",
  updatedAt: "2026-08-28T07:00:00Z",
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

function documentEditor(): HTMLElement {
  const editor = screen.getAllByRole("textbox").find((element) =>
    element instanceof HTMLTextAreaElement
    || element.isContentEditable
    || element.getAttribute("aria-multiline") === "true",
  );
  if (editor === undefined) {
    throw new Error("Expected the document body to be exposed as an accessible multiline textbox.");
  }
  return editor;
}

function editorText(editor: HTMLElement): string {
  return editor instanceof HTMLTextAreaElement ? editor.value : editor.textContent ?? "";
}

function replaceEditorText(editor: HTMLElement, value: string) {
  if (editor instanceof HTMLTextAreaElement) {
    fireEvent.change(editor, { target: { value } });
    return;
  }
  editor.textContent = value;
  fireEvent.input(editor, { data: value, inputType: "insertText" });
}

describe("EDIT-001 local editor acceptance", () => {
  beforeEach(() => {
    window.location.hash = "#/documents/document-1";
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("shows loading state, then loads and edits multiline Unicode text without a REST body write", async () => {
    let resolveDocument!: (value: Response) => void;
    const pendingDocument = new Promise<Response>((resolve) => {
      resolveDocument = resolve;
    });
    const fetchMock = vi.fn();
    authenticated(fetchMock);
    fetchMock
      .mockReturnValueOnce(pendingDocument)
      .mockResolvedValueOnce(response(200, { owner: user, permissions: [] }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByText("Loading document...")).toHaveAttribute("role", "status");
    await act(async () => resolveDocument(response(200, ownerDocument)));

    await screen.findByRole("heading", { name: "Unicode notes" });
    const editor = documentEditor();
    expect(editor).toHaveAccessibleName();
    expect(editorText(editor)).toBe("A😀B\nsecond line");

    replaceEditorText(editor, "A😀B\nsecond line\n終わり");
    expect(editorText(editor)).toBe("A😀B\nsecond line\n終わり");

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
    const documentWrites = fetchMock.mock.calls.filter(([url, init]) =>
      url === "/api/v1/documents/document-1"
      && (init as RequestInit | undefined)?.method !== undefined,
    );
    expect(documentWrites).toHaveLength(0);
  });

  it("supports an empty editor and preserves UTF-16 cursor and selection offsets", async () => {
    const emptyDocument = { ...ownerDocument, content: "" };
    const fetchMock = vi.fn();
    authenticated(fetchMock);
    fetchMock
      .mockResolvedValueOnce(response(200, emptyDocument))
      .mockResolvedValueOnce(response(200, { owner: user, permissions: [] }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    await screen.findByRole("heading", { name: "Unicode notes" });
    const editor = documentEditor();
    expect(editorText(editor)).toBe("");

    replaceEditorText(editor, "A😀B");
    expect(editorText(editor).length).toBe(4);

    if (editor instanceof HTMLTextAreaElement) {
      editor.setSelectionRange(1, 3);
      fireEvent.select(editor);
      expect(editor.selectionStart).toBe(1);
      expect(editor.selectionEnd).toBe(3);
    } else {
      const text = editor.firstChild;
      expect(text).not.toBeNull();
      const selection = window.getSelection();
      const range = document.createRange();
      range.setStart(text!, 1);
      range.setEnd(text!, 3);
      selection?.removeAllRanges();
      selection?.addRange(range);
      fireEvent.select(editor);
      expect(selection?.anchorOffset).toBe(1);
      expect(selection?.focusOffset).toBe(3);
    }
  });

  it("allows an editor to edit text and title while retaining owner-only boundaries", async () => {
    const sharedDocument = {
      ...ownerDocument,
      id: "document-2",
      title: "Shared notes",
      content: "Shared text",
      permission: "EDITOR" as const,
    };
    window.location.hash = "#/documents/document-2";
    const fetchMock = vi.fn();
    authenticated(fetchMock);
    fetchMock
      .mockResolvedValueOnce(response(200, sharedDocument))
      .mockResolvedValueOnce(response(200, { ...sharedDocument, title: "Renamed by editor" }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    await screen.findByRole("heading", { name: "Shared notes" });
    const editor = documentEditor();
    replaceEditorText(editor, "Locally edited shared text");
    expect(editorText(editor)).toBe("Locally edited shared text");
    expect(screen.queryByRole("heading", { name: "Sharing" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete document" })).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Document title"), { target: { value: "Renamed by editor" } });
    fireEvent.submit(screen.getByLabelText("Document title").closest("form")!);
    expect(await screen.findByRole("heading", { name: "Renamed by editor" })).toBeInTheDocument();
  });

  it("keeps load failures visible and lets keyboard users return to the document list", async () => {
    const fetchMock = vi.fn();
    authenticated(fetchMock);
    fetchMock
      .mockResolvedValueOnce(response(503, {
        error: { code: "SERVICE_UNAVAILABLE", message: "Documents are temporarily unavailable." },
      }))
      .mockResolvedValueOnce(response(200, { documents: [], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Documents are temporarily unavailable.");
    const returnButton = screen.getByRole("button", { name: "Return to documents" });
    returnButton.focus();
    fireEvent.keyDown(returnButton, { key: "Enter" });
    fireEvent.click(returnButton);

    expect(await screen.findByRole("heading", { name: "Your workspace" })).toBeInTheDocument();
  });
});
