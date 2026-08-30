import { expect, test } from "@playwright/test";

/**
 * End-to-end integration test exercising:
 * React frontend -> real Spring Boot REST API -> real PostgreSQL database
 * without mocking any document or authentication API routes.
 */
test.describe("Real-stack document and sharing E2E integration", () => {
  test.beforeEach(async ({ request }) => {
    // Check if real backend is running on http://localhost:8080
    try {
      const res = await request.get("http://localhost:8080/actuator/health");
      if (!res.ok()) {
        test.skip(true, "Real backend is not running on http://localhost:8080");
      }
    } catch {
      test.skip(true, "Real backend is not running on http://localhost:8080");
    }
  });

  test("executes complete real frontend -> Spring Boot -> PostgreSQL document lifecycle", async ({ page }) => {
    const timestamp = Date.now();
    const ownerUser = `owner_${timestamp}`;
    const ownerEmail = `owner_${timestamp}@example.com`;
    const editorUser = `editor_${timestamp}`;
    const editorEmail = `editor_${timestamp}@example.com`;
    const password = "Password123!";

    // 1. Register real owner account
    await page.goto("/#/register");
    await page.getByLabel("Username").fill(ownerUser);
    await page.getByLabel("Email").fill(ownerEmail);
    await page.getByLabel("Display name").fill("Real Stack Owner");
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Create account" }).click();

    // Verify successful login & workspace navigation
    await expect(page.getByRole("heading", { name: "Your workspace" })).toBeVisible({ timeout: 10000 });
    await expect(page.locator(".app-navigation").getByText("Real Stack Owner")).toBeVisible();

    // 2. Create document from browser
    await page.getByLabel("Title").fill("Real Stack Architecture");
    await page.getByLabel("Initial text (optional)").fill("Real PostgreSQL initial snapshot text");
    await page.getByRole("button", { name: "Create document" }).click();

    // 3. Verify document detail loaded and content displayed
    await expect(page.getByRole("heading", { name: "Real Stack Architecture" })).toBeVisible();
    await expect(page.getByText("Real PostgreSQL initial snapshot text")).toBeVisible();
    await expect(page.getByText("You control access and deletion.")).toBeVisible();

    // 4. Verify document appears in workspace list
    await page.goto("/#/documents");
    await expect(page.getByText("Real Stack Architecture")).toBeVisible();

    // 5. Open and rename document
    await page.getByText("Real Stack Architecture").click();
    await expect(page.getByRole("heading", { name: "Real Stack Architecture" })).toBeVisible();
    await page.getByLabel("Document title").fill("Renamed Architecture Spec");
    await page.getByRole("button", { name: "Save title" }).click();
    await expect(page.getByRole("heading", { name: "Renamed Architecture Spec" })).toBeVisible();

    // Reload page to verify title persistence from PostgreSQL
    await page.reload();
    await expect(page.getByRole("heading", { name: "Renamed Architecture Spec" })).toBeVisible();

    // 6. Register second user (editor)
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    await page.goto("/#/register");
    await page.getByLabel("Username").fill(editorUser);
    await page.getByLabel("Email").fill(editorEmail);
    await page.getByLabel("Display name").fill("Real Stack Editor");
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByRole("heading", { name: "Your workspace" })).toBeVisible({ timeout: 10000 });
    await expect(page.locator(".app-navigation").getByText("Real Stack Editor")).toBeVisible();

    // 7. Log back in as owner to grant access
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    await page.getByLabel("Username or email").fill(ownerUser);
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.locator(".app-navigation").getByText("Real Stack Owner")).toBeVisible();

    await page.getByText("Renamed Architecture Spec").click();
    await expect(page.getByRole("heading", { name: "Renamed Architecture Spec" })).toBeVisible();

    await page.getByLabel("Username or email").fill(editorEmail);
    await page.getByRole("button", { name: "Grant editor access" }).click();
    await expect(page.getByText(`Real Stack Editor (@${editorUser}) — Editor`)).toBeVisible();

    // 8. Log in as editor and verify shared document visibility
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    await page.getByLabel("Username or email").fill(editorUser);
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.locator(".app-navigation").getByText("Real Stack Editor")).toBeVisible();

    await expect(page.getByText("Renamed Architecture Spec")).toBeVisible();
    await page.getByText("Renamed Architecture Spec").click();

    // 9. Verify editor cannot perform owner-only actions
    await expect(page.getByText("You can rename this document.")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Sharing" })).not.toBeVisible();
    await expect(page.getByRole("button", { name: "Delete document" })).not.toBeVisible();

    // Rename as editor
    await page.getByLabel("Document title").fill("Editor Modified Spec");
    await page.getByRole("button", { name: "Save title" }).click();
    await expect(page.getByRole("heading", { name: "Editor Modified Spec" })).toBeVisible();

    // 10. Log back in as owner to revoke access
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    await page.getByLabel("Username or email").fill(ownerUser);
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.locator(".app-navigation").getByText("Real Stack Owner")).toBeVisible();

    await page.getByText("Editor Modified Spec").click();
    await page.getByRole("button", { name: "Revoke access" }).click();
    await expect(page.getByText("No editors have access yet.")).toBeVisible();

    // 11. Verify former editor loses access
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    await page.getByLabel("Username or email").fill(editorUser);
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText("No documents yet. Create your first document to get started.")).toBeVisible();

    // 12. Log in as owner and delete document
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    await page.getByLabel("Username or email").fill(ownerUser);
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.locator(".app-navigation").getByText("Real Stack Owner")).toBeVisible();

    await page.getByText("Editor Modified Spec").click();
    await page.getByRole("button", { name: "Delete document" }).click();
    await expect(page.getByRole("heading", { name: "Your workspace" })).toBeVisible();
    await expect(page.getByText("No documents yet. Create your first document to get started.")).toBeVisible();
  });

  test("executes real-time two-browser concurrent collaboration over WebSockets and converges", async ({ browser }) => {
    const timestamp = Date.now();
    const ownerUser = `collab_owner_${timestamp}`;
    const ownerEmail = `collab_owner_${timestamp}@example.com`;
    const editorUser = `collab_editor_${timestamp}`;
    const editorEmail = `collab_editor_${timestamp}@example.com`;
    const password = "Password123!";

    // Create two separate browser contexts for two distinct users/tabs
    const contextOwner = await browser.newContext();
    const contextEditor = await browser.newContext();
    const pageOwner = await contextOwner.newPage();
    const pageEditor = await contextEditor.newPage();

    // 1. Owner registration & document creation
    await pageOwner.goto("/#/register");
    await pageOwner.getByLabel("Username").fill(ownerUser);
    await pageOwner.getByLabel("Email").fill(ownerEmail);
    await pageOwner.getByLabel("Display name").fill("Collab Owner");
    await pageOwner.getByLabel("Password").fill(password);
    await pageOwner.getByRole("button", { name: "Create account" }).click();
    await expect(pageOwner.getByRole("heading", { name: "Your workspace" })).toBeVisible({ timeout: 10000 });

    await pageOwner.getByLabel("Title").fill("Realtime Shared Doc");
    await pageOwner.getByLabel("Initial text (optional)").fill("Hello");
    await pageOwner.getByRole("button", { name: "Create document" }).click();
    await expect(pageOwner.getByRole("heading", { name: "Realtime Shared Doc" })).toBeVisible();

    const documentUrl = pageOwner.url();

    // 2. Editor registration
    await pageEditor.goto("/#/register");
    await pageEditor.getByLabel("Username").fill(editorUser);
    await pageEditor.getByLabel("Email").fill(editorEmail);
    await pageEditor.getByLabel("Display name").fill("Collab Editor");
    await pageEditor.getByLabel("Password").fill(password);
    await pageEditor.getByRole("button", { name: "Create account" }).click();
    await expect(pageEditor.getByRole("heading", { name: "Your workspace" })).toBeVisible({ timeout: 10000 });

    // 3. Owner grants editor access
    await pageOwner.getByLabel("Username or email").fill(editorEmail);
    await pageOwner.getByRole("button", { name: "Grant editor access" }).click();
    await expect(pageOwner.getByText(`Collab Editor (@${editorUser}) — Editor`)).toBeVisible();

    // 4. Editor opens shared document URL
    await pageEditor.goto(documentUrl);
    await expect(pageEditor.getByRole("heading", { name: "Realtime Shared Doc" })).toBeVisible();

    const editorOwner = pageOwner.getByLabel("Document text editor");
    const editorCollab = pageEditor.getByLabel("Document text editor");

    await expect(editorOwner).toHaveValue("Hello");
    await expect(editorCollab).toHaveValue("Hello");

    // 5. Concurrent edits: Owner appends " World"
    await editorOwner.focus();
    await editorOwner.press("End");
    await editorOwner.pressSequentially(" World");

    // 6. Verify Editor receives real-time update over WebSocket
    await expect.poll(async () => editorCollab.inputValue()).toBe("Hello World");

    // 7. Editor appends "!"
    await editorCollab.focus();
    await editorCollab.press("End");
    await editorCollab.pressSequentially("!");

    // 8. Verify Owner receives real-time update over WebSocket
    await expect.poll(async () => editorOwner.inputValue()).toBe("Hello World!");

    // 9. Reload both pages and verify persistence recovery from PostgreSQL matches
    await pageOwner.reload();
    await pageEditor.reload();

    await expect(pageOwner.getByLabel("Document text editor")).toHaveValue("Hello World!");
    await expect(pageEditor.getByLabel("Document text editor")).toHaveValue("Hello World!");

    await contextOwner.close();
    await contextEditor.close();
  });
});
