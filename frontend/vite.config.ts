import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test-setup.ts",
    // e2e/ belongs to Playwright, which drives a real browser rather than jsdom.
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
  },
});
