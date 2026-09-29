import { defineConfig, devices } from "@playwright/test";

/**
 * Real-browser gate for what jsdom cannot see: layout at narrow widths, focus rendering, and
 * colour contrast. The API is stubbed per spec, so this suite needs no backend or database.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? "list" : [["list"]],
  use: {
    baseURL: "http://127.0.0.1:4173",
    trace: "retain-on-failure",
  },
  projects: [
    {
      // 360 px is the narrow-width floor the Buyer interface commits to.
      name: "narrow",
      use: { ...devices["Desktop Chrome"], viewport: { width: 360, height: 780 } },
    },
  ],
  webServer: {
    command: "npm run preview -- --host 127.0.0.1 --port 4173 --strictPort",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
