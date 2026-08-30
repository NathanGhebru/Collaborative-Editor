import { defineConfig, devices } from "@playwright/test";

const frontendHost = process.env.FRONTEND_HOST ?? "localhost";
const baseUrl = process.env.PLAYWRIGHT_BASE_URL ?? `http://${frontendHost}:5173`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  use: {
    baseURL: baseUrl,
    trace: "on-first-retry",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `npm run dev -- --host ${frontendHost}`,
    port: 5173,
    reuseExistingServer: !process.env.CI,
  },
});
