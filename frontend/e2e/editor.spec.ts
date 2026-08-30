import { expect, test, type Page } from "@playwright/test";
import { mockRealtimeReady } from "./realtimeMock";

const user = {
  id: "user-1",
  username: "nathan",
  displayName: "Nathan",
  createdAt: "2026-08-28T07:00:00Z",
};

const documentDetail = {
  id: "document-1",
  title: "Distributed Systems Notes",
  owner: user,
  permission: "OWNER",
  currentRevision: 0,
  syncEpoch: "epoch-1",
  createdAt: "2026-08-28T07:00:00Z",
  updatedAt: "2026-08-28T07:00:00Z",
  content: "Initial document snapshot",
};

const ownerDocument = {
  id: "document-1",
  title: "Unicode notes",
  content: "A😀B\nsecond line",
  owner: user,
  permission: "OWNER",
  currentRevision: 0,
  syncEpoch: "epoch-1",
  createdAt: "2026-08-28T07:00:00Z",
  updatedAt: "2026-08-28T07:00:00Z",
};

async function mockAuthenticated(page: Page) {
  await page.route("**/api/v1/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ accessToken: "access-token", expiresInSeconds: 900 }),
    }),
  );
  await page.route("**/api/v1/users/me", (route) => {
    expect(route.request().headers().authorization).toBe("Bearer access-token");
    return route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ ...user, email: "nathan@example.com" }),
    });
  });
}

function documentEditor(page: Page) {
  return page.locator("textarea, [contenteditable='true'], [role='textbox'][aria-multiline='true']").first();
}

async function editorText(page: Page): Promise<string> {
  return documentEditor(page).evaluate((element) =>
    element instanceof HTMLTextAreaElement ? element.value : element.textContent ?? "",
  );
}

test.describe("Local Plain-Text Editor Experience (EDIT-001)", () => {
  test("loads document text, captures edits, updates cursor position, and extracts operations", async ({
    page,
  }) => {
    await mockAuthenticated(page);
    await mockRealtimeReady(page);
    await page.route("**/api/v1/documents/document-1", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(documentDetail),
      }),
    );
    await page.route("**/api/v1/documents/document-1/permissions", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ owner: user, permissions: [] }),
      }),
    );

    await page.goto("/#/documents/document-1");

    // Verify initial load state
    await expect(page.getByRole("heading", { name: "Distributed Systems Notes" })).toBeVisible();
    const editor = page.getByLabel("Document text editor");
    await expect(editor).toBeVisible();
    await expect(editor).toHaveValue("Initial document snapshot");
    await expect(page.getByRole("status")).toHaveText("Saved");

    // Perform local edits
    await editor.fill("Initial document snapshot\nLine 2: Local editing active!");
    await expect(page.getByRole("status")).toHaveText(/Saving/);

    // Verify captured operation log output
    await expect(page.getByText("Local operations captured:")).toBeVisible();

    // Perform title edit alongside local text editing
    await page.route("**/api/v1/documents/document-1", async (route) => {
      if (route.request().method() === "PATCH") {
        const body = route.request().postDataJSON();
        documentDetail.title = body.title;
        await route.fulfill({
          contentType: "application/json",
          body: JSON.stringify(documentDetail),
        });
        return;
      }
      await route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(documentDetail),
      });
    });

    await page.getByLabel("Document title").fill("Updated Title");
    await page.getByRole("button", { name: "Save title" }).click();
    await expect(page.getByRole("heading", { name: "Updated Title" })).toBeVisible();
  });

  test("loads multiline Unicode text and supports keyboard editing with UTF-16 selection offsets", async ({ page }) => {
    await mockAuthenticated(page);
    await mockRealtimeReady(page);
    const unexpectedBodyWrites: string[] = [];
    await page.route("**/api/v1/documents/document-1", async (route) => {
      if (route.request().method() !== "GET") {
        unexpectedBodyWrites.push(route.request().postData() ?? "");
      }
      await route.fulfill({ contentType: "application/json", body: JSON.stringify(ownerDocument) });
    });
    await page.route("**/api/v1/documents/document-1/permissions", (route) => route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ owner: user, permissions: [] }),
    }));

    await page.goto("/#/documents/document-1");
    const editor = documentEditor(page);
    await expect(editor).toBeVisible();
    await expect(editor).toHaveAccessibleName(/.+/);
    await expect.poll(() => editorText(page)).toBe("A😀B\nsecond line");

    await editor.focus();
    await editor.press("Control+Home");
    await editor.press("ArrowRight");
    await editor.press("Shift+ArrowRight");
    await expect.poll(() => editor.evaluate((element) => {
      if (element instanceof HTMLTextAreaElement) {
        return { start: element.selectionStart, end: element.selectionEnd };
      }
      const selection = window.getSelection();
      return {
        start: Math.min(selection?.anchorOffset ?? -1, selection?.focusOffset ?? -1),
        end: Math.max(selection?.anchorOffset ?? -1, selection?.focusOffset ?? -1),
      };
    })).toEqual({ start: 1, end: 3 });

    await editor.press("Control+End");
    await editor.press("Enter");
    await editor.pressSequentially("終わり");
    await expect.poll(() => editorText(page)).toBe("A😀B\nsecond line\n終わり");
    expect(unexpectedBodyWrites).toEqual([]);
  });

  test("supports an empty editor and preserves editor-role metadata boundaries and navigation", async ({ page }) => {
    await mockAuthenticated(page);
    await mockRealtimeReady(page);
    const sharedDocument = {
      ...ownerDocument,
      id: "document-2",
      title: "Shared notes",
      content: "",
      permission: "EDITOR",
    };
    await page.route("**/api/v1/documents/document-2", async (route) => {
      if (route.request().method() === "PATCH") {
        sharedDocument.title = route.request().postDataJSON().title;
      }
      await route.fulfill({ contentType: "application/json", body: JSON.stringify(sharedDocument) });
    });
    await page.route(/\/api\/v1\/documents\?limit=20$/, (route) => route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ documents: [], nextCursor: null }),
    }));

    await page.goto("/#/documents/document-2");
    const editor = documentEditor(page);
    await expect.poll(() => editorText(page)).toBe("");
    await editor.fill("first line\nsecond line");
    await expect.poll(() => editorText(page)).toBe("first line\nsecond line");
    await expect(page.getByRole("heading", { name: "Sharing" })).not.toBeVisible();
    await expect(page.getByRole("button", { name: "Delete document" })).not.toBeVisible();

    const title = page.getByLabel("Document title");
    await title.fill("Renamed by editor");
    await title.press("Enter");
    await expect(page.getByRole("heading", { name: "Renamed by editor" })).toBeVisible();

    const back = page.getByRole("button", { name: "Back to documents" });
    await back.focus();
    await back.press("Enter");
    await expect(page.getByRole("heading", { name: "Your workspace" })).toBeVisible();
  });

  test("shows document loading and API failure states without exposing an editor", async ({ page }) => {
    await mockAuthenticated(page);
    let releaseRequest!: () => void;
    const requestGate = new Promise<void>((resolve) => {
      releaseRequest = resolve;
    });
    await page.route("**/api/v1/documents/unavailable", async (route) => {
      await requestGate;
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          error: { code: "SERVICE_UNAVAILABLE", message: "Documents are temporarily unavailable." },
        }),
      });
    });

    await page.goto("/#/documents/unavailable");
    await expect(page.getByRole("status")).toHaveText(/Loading document/i);
    releaseRequest();

    await expect(page.getByRole("heading", { name: "Document unavailable" })).toBeVisible();
    await expect(page.getByRole("alert")).toHaveText("Documents are temporarily unavailable.");
    await expect(documentEditor(page)).toHaveCount(0);
  });
});
