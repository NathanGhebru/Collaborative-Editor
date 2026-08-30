import { expect, test, type Page } from "@playwright/test";

const user = {
  id: "9cd819ab-20de-4356-8870-69757480c0d1",
  username: "nathan",
  email: "nathan@example.com",
  displayName: "Nathan",
  createdAt: "2026-08-28T07:00:00Z",
};

async function mockNoSession(page: Page, code = "REFRESH_TOKEN_MISSING") {
  await page.route("**/api/v1/auth/refresh", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({
        error: { code, message: "No active session.", requestId: "e2e" },
      }),
    }),
  );
}

test("registers through the documented API contract", async ({ page }) => {
  await mockNoSession(page);
  await page.route("**/api/v1/auth/register", async (route) => {
    expect(route.request().postDataJSON()).toEqual({
      username: "nathan_1",
      email: "nathan@example.com",
      password: "password123",
      displayName: "Nathan",
    });
    await route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({ user, accessToken: "access-token", expiresInSeconds: 900 }),
    });
  });

  await page.goto("/#/register");
  await page.getByLabel("Username").fill(" Nathan_1 ");
  await page.getByLabel("Email").fill(" Nathan@Example.com ");
  await page.getByLabel("Display name").fill(" Nathan ");
  await page.getByLabel("Password").fill("password123");
  await page.getByRole("button", { name: "Create account" }).click();

  await expect(page.getByRole("heading", { name: "Welcome, Nathan" })).toBeVisible();
});

test("rejects an unauthenticated protected route and presents a safe login error", async ({ page }) => {
  await mockNoSession(page, "REFRESH_TOKEN_REVOKED");
  await page.route("**/api/v1/auth/login", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({
        error: {
          code: "INVALID_CREDENTIALS",
          message: "Invalid username/email or password.",
          requestId: "e2e",
        },
      }),
    }),
  );

  await page.goto("/#/app");
  await expect(page.getByRole("heading", { name: "Sign in to continue" })).toBeVisible();
  await page.getByLabel("Username or email").fill("nathan");
  await page.getByLabel("Password").fill("wrong-password");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page.getByRole("alert")).toHaveText("Invalid username/email or password.");
});

test("restores a protected route from the refresh cookie and logs out", async ({ page }) => {
  await page.route("**/api/v1/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ accessToken: "refreshed-token", expiresInSeconds: 900 }),
    }),
  );
  await page.route("**/api/v1/users/me", (route) => {
    expect(route.request().headers().authorization).toBe("Bearer refreshed-token");
    return route.fulfill({ contentType: "application/json", body: JSON.stringify(user) });
  });
  await page.route("**/api/v1/auth/logout", (route) => route.fulfill({ status: 204 }));

  await page.goto("/#/app");
  await expect(page.getByRole("heading", { name: "Welcome, Nathan" })).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();

  await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
});
