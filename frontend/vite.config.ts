/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies the API so the browser talks to one origin (no CORS,
// no service change). Host is left unchanged so the service's Link header points
// back at the dev server. Ports are main's (5173 for this server, 8080 for the
// service, FE24); override with VITE_PORT and API_URL if they are taken.
export const devPort = Number(process.env.VITE_PORT ?? 5173);
export const apiUrl = process.env.API_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: devPort,
    strictPort: true,
    proxy: {
      '/v2': { target: apiUrl, changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
});
