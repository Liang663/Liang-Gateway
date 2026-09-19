import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  base: "/console/",
  server: {
    proxy: Object.fromEntries(
      ["/admin", "/v1", "/mcp", "/health"].map((path) => [
        path,
        {
          target: process.env.GATEWAY_URL || "http://127.0.0.1:8080",
          changeOrigin: true,
        },
      ]),
    ),
  },
  test: { include: ["src/**/*.test.ts"] },
});
