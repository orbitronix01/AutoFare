import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// es2022 for the top-level await that loads demo data in dev (see supabase.js)
export default defineConfig({ plugins: [react()], build: { target: "es2022" } });
