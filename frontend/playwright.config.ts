import { defineConfig, devices } from "@playwright/test";

/**
 * Each checkout needs its own preview server. With one fixed port and `reuseExistingServer`, a
 * second worktree does not fail loudly — it reuses the first worktree's server and reports on the
 * wrong build. `PLAYWRIGHT_PORT` is what a coordinator assigns when it runs tickets in parallel;
 * without it the port is derived from this checkout's directory, so two worktrees do not land on
 * the same one by accident. Reuse then only ever finds this checkout's own server.
 */
function previewPort() {
  const assigned = Number(process.env.PLAYWRIGHT_PORT);
  if (Number.isInteger(assigned) && assigned > 0 && assigned < 65536) {
    return assigned;
  }
  let hash = 0x811c9dc5;
  for (const character of process.cwd()) {
    hash = Math.imul(hash ^ character.charCodeAt(0), 0x01000193) >>> 0;
  }
  return 4200 + (hash % 700);
}

const port = previewPort();
const origin = `http://127.0.0.1:${port}`;

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
    baseURL: origin,
    trace: "retain-on-failure",
  },
  projects: [
    {
      // 360 px is the narrow-width floor the Buyer interface commits to.
      name: "narrow",
      use: { ...devices["Desktop Chrome"], viewport: { width: 360, height: 780 }, colorScheme: "light" },
    },
    {
      // Every layout and contrast check runs again in the dark theme.
      name: "narrow-dark",
      use: { ...devices["Desktop Chrome"], viewport: { width: 360, height: 780 }, colorScheme: "dark" },
    },
  ],
  webServer: {
    command: `npm run preview -- --host 127.0.0.1 --port ${port} --strictPort`,
    url: origin,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
