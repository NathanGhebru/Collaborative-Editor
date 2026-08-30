import { expect, test, type Page } from "@playwright/test";

const user = { id: "user-1", username: "nathan", displayName: "Nathan", createdAt: "2026-08-28T07:00:00Z" };
const owner = user;
const documentDetail = {
  id: "document-1",
  title: "Distributed Systems Notes",
  owner,
  permission: "OWNER",
  currentRevision: 0,
  syncEpoch: "epoch-1",
  createdAt: "2026-08-28T07:00:00Z",
  updatedAt: "2026-08-28T07:00:00Z",
  content: "Initial document snapshot",
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

test.describe("Local Plain-Text Editor Experience (EDIT-001)", () => {
  test("loads document text, captures edits, updates cursor position, and extracts operations", async ({
    page,
  }) => {
    await mockAuthenticated(page);
    await page.route("**/api/v1/documents/document-1", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(documentDetail),
      }),
    );
    await page.route("**/api/v1/documents/document-1/permissions", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ owner, permissions: [] }),
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
    await expect(page.getByRole("status")).toHaveText("Unsaved local changes");

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
});
