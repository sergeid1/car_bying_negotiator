import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";
import clientHeaders from "./client-headers.json" with { type: "json" };

export default defineConfig({
  plugins: [react()],
  // Production client headers only: HMR/React refresh retain their development behavior.
  preview: { headers: clientHeaders },
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
