import { expect, test, type Page } from "@playwright/test";

const user = { id: "user-1", username: "nathan", displayName: "Nathan", createdAt: "2026-08-28T07:00:00Z" };
const owner = user;
const collaborator = { id: "user-2", username: "collaborator", displayName: "Collaborator" };
const summary = {
  id: "document-1", title: "Distributed Systems Notes", owner, permission: "OWNER",
  currentRevision: 0, syncEpoch: "epoch-1", createdAt: "2026-08-28T07:00:00Z", updatedAt: "2026-08-28T07:00:00Z",
};

async function mockAuthenticated(page: Page) {
  await page.route("**/api/v1/auth/refresh", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ accessToken: "access-token", expiresInSeconds: 900 }),
  }));
  await page.route("**/api/v1/users/me", (route) => {
    expect(route.request().headers().authorization).toBe("Bearer access-token");
    return route.fulfill({ contentType: "application/json", body: JSON.stringify({ ...user, email: "nathan@example.com" }) });
  });
}

test("lists documents, renders the empty state, and follows a cursor", async ({ page }) => {
  await mockAuthenticated(page);
  await page.route(/\/api\/v1\/documents\?limit=20(?:&cursor=next-page)?$/, (route) => {
    expect(route.request().headers().authorization).toBe("Bearer access-token");
    if (route.request().url().includes("cursor=next-page")) {
      return route.fulfill({ contentType: "application/json", body: JSON.stringify({
        documents: [{ ...summary, id: "document-2", title: "Later document" }], nextCursor: null,
      }) });
    }
    return route.fulfill({ contentType: "application/json", body: JSON.stringify({ documents: [summary], nextCursor: "next-page" }) });
  });

  await page.goto("/#/documents");
  await expect(page.getByText("Distributed Systems Notes")).toBeVisible();
  await page.getByRole("button", { name: "Load more documents" }).click();
  await expect(page.getByText("Later document")).toBeVisible();

  await page.route(/\/api\/v1\/documents\?limit=20$/, (route) => route.fulfill({
    contentType: "application/json", body: JSON.stringify({ documents: [], nextCursor: null }),
  }));
  await page.reload();
  await expect(page.getByText("No documents yet. Create your first document to get started.")).toBeVisible();
});

test("creates, opens, renames, shares, revokes, and deletes an owner document", async ({ page }) => {
  await mockAuthenticated(page);
  let permissions = [] as Array<{ user: typeof collaborator; role: string; createdAt: string }>;
  const detail = { ...summary, content: "Initial text" };

  await page.route(/\/api\/v1\/documents\?limit=20$/, (route) => route.fulfill({
    contentType: "application/json", body: JSON.stringify({ documents: [], nextCursor: null }),
  }));
  await page.route("**/api/v1/documents", async (route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }
    expect(route.request().postDataJSON()).toEqual({ title: "Distributed Systems Notes", initialContent: "Initial text" });
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(detail) });
  });
  await page.route("**/api/v1/documents/document-1", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({ contentType: "application/json", body: JSON.stringify(detail) });
      return;
    }
    if (route.request().method() === "PATCH") {
      const body = route.request().postDataJSON();
      detail.title = body.title;
      await route.fulfill({ contentType: "application/json", body: JSON.stringify(detail) });
      return;
    }
    await route.fulfill({ status: 204 });
  });
  await page.route("**/api/v1/documents/document-1/permissions", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({ contentType: "application/json", body: JSON.stringify({ owner, permissions }) });
      return;
    }
    expect(route.request().postDataJSON()).toEqual({ userIdentifier: "collaborator@example.com", role: "EDITOR" });
    const permission = { user: collaborator, role: "EDITOR", createdAt: "2026-08-28T08:15:00Z" };
    permissions = [permission];
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(permission) });
  });
  await page.route("**/api/v1/documents/document-1/permissions/user-2", (route) => {
    permissions = [];
    return route.fulfill({ status: 204 });
  });

  await page.goto("/#/documents");
  await page.getByLabel("Title").fill("Distributed Systems Notes");
  await page.getByLabel("Initial text (optional)").fill("Initial text");
  await page.getByRole("button", { name: "Create document" }).click();
  await expect(page.getByRole("heading", { name: "Distributed Systems Notes" })).toBeVisible();

  await page.getByLabel("Document title").fill("Renamed notes");
  await page.getByRole("button", { name: "Save title" }).click();
  await expect(page.getByRole("heading", { name: "Renamed notes" })).toBeVisible();
  await page.getByLabel("Username or email").fill("collaborator@example.com");
  await page.getByRole("button", { name: "Grant editor access" }).click();
  await expect(page.getByText("Collaborator (@collaborator) — Editor")).toBeVisible();
  await page.getByRole("button", { name: "Revoke access" }).click();
  await expect(page.getByText("No editors have access yet.")).toBeVisible();
  await page.getByRole("button", { name: "Delete document" }).click();
  await expect(page.getByRole("heading", { name: "Your workspace" })).toBeVisible();
});

test("shows editor and denied-document behavior", async ({ page }) => {
  await mockAuthenticated(page);
  const editorDetail = { ...summary, id: "document-2", title: "Shared notes", permission: "EDITOR", content: "Shared snapshot" };
  await page.route("**/api/v1/documents/document-2", (route) => route.fulfill({
    contentType: "application/json", body: JSON.stringify(editorDetail),
  }));
  await page.route("**/api/v1/documents/private-document", (route) => route.fulfill({
    status: 404,
    contentType: "application/json",
    body: JSON.stringify({ error: { code: "DOCUMENT_NOT_FOUND", message: "The requested document does not exist." } }),
  }));

  await page.goto("/#/documents/document-2");
  await expect(page.getByText("You can rename this document.")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Sharing" })).not.toBeVisible();
  await expect(page.getByRole("button", { name: "Delete document" })).not.toBeVisible();

  await page.goto("/#/documents/private-document");
  await expect(page.getByRole("heading", { name: "Document unavailable" })).toBeVisible();
  await expect(page.getByRole("alert")).toHaveText("The requested document does not exist.");
});
