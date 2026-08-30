import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

const backendUrl = process.env.VITE_BACKEND_URL ?? "http://localhost:8080";
const frontendHost = process.env.FRONTEND_HOST ?? "localhost";

export default defineConfig({
  plugins: [react()],
  server: {
    host: frontendHost,
    port: Number(process.env.FRONTEND_PORT ?? 5173),
    proxy: {
      "/api": {
        target: backendUrl,
        changeOrigin: true,
      },
      "/actuator": {
        target: backendUrl,
        changeOrigin: true,
      },
      "/ws": {
        target: backendUrl,
        changeOrigin: true,
        ws: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/setupTests.ts",
    include: ["src/**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}"],
  },
});
