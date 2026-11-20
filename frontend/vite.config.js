import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In the compose stack, API_PROXY_TARGET points the /api calls at the backend container.
const backend = process.env.API_PROXY_TARGET ?? "http://localhost:8000";

export default defineConfig({ plugins: [react()], server: { port: 3000, proxy: { "/api": backend } } });
