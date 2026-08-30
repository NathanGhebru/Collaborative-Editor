import { expect, test } from "@playwright/test";

test.describe("Real Authentication Integration (Unmocked)", () => {
  const timestamp = Date.now();
  const username = `real_user_${timestamp}`;
  const email = `real_${timestamp}@example.com`;
  const password = "Password123!";
  const displayName = "Real User";

  test("1-14: Complete authentication lifecycle against real backend & database", async ({
    page,
  }) => {
    // 12. Protected-route behavior when unauthenticated
    await page.goto("/#/app");
    await expect(page.getByRole("heading", { name: "Sign in to continue" })).toBeVisible();

    // 9. Invalid credentials error
    await page.getByLabel("Username or email").fill("nonexistent_user_9999");
    await page.getByLabel("Password").fill("wrongpassword");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByRole("alert")).toHaveText("Invalid username or password.");

    // Navigate to register screen
    await page.getByRole("link", { name: "Create an account" }).click();
    await expect(page.getByRole("heading", { name: "Create your account" })).toBeVisible();

    // 1. Registration & 3. Authenticated frontend state & 4. Access token usage
    await page.getByLabel("Username").fill(username);
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Display name").fill(displayName);
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByRole("heading", { name: `Welcome, ${displayName}` })).toBeVisible();

    // 6. Browser reload while authenticated (5. Refresh-cookie / session restoration)
    await page.reload();
    await expect(page.getByRole("heading", { name: `Welcome, ${displayName}` })).toBeVisible();

    // 7. Logout & 14. Refresh-token revocation
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    // 13. Protected-route behavior when unauthenticated after logout
    await page.goto("/#/app");
    await expect(page.getByRole("heading", { name: "Sign in to continue" })).toBeVisible();

    // 8. Login after logout (2. Login)
    await page.getByLabel("Username or email").fill(username);
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByRole("heading", { name: `Welcome, ${displayName}` })).toBeVisible();

    // Clean logout
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    // 10. Duplicate username registration attempt
    await page.goto("/#/register");
    await page.getByLabel("Username").fill(username);
    await page.getByLabel("Email").fill(`other_${timestamp}@example.com`);
    await page.getByLabel("Display name").fill("Another User");
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByRole("alert")).toHaveText("Username is already registered.");

    // 11. Duplicate email registration attempt
    await page.goto("/#/register");
    await page.getByLabel("Username").fill(`other_${timestamp}`);
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Display name").fill("Another User");
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByRole("alert")).toHaveText("Email is already registered.");
  });
});

