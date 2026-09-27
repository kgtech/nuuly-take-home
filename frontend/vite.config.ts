/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies the API so the browser talks to one origin (no CORS,
// no service change). Host is left unchanged so the service's Link header points
// back at the dev server. Ports are off the defaults (5173, 8080) so a test run
// never collides with another project's dev server or service (FE24); override
// with VITE_PORT and API_URL.
export const devPort = Number(process.env.VITE_PORT ?? 15173);
export const apiUrl = process.env.API_URL ?? 'http://localhost:18080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: devPort,
    strictPort: true,
    proxy: {
      '/inventory': { target: apiUrl, changeOrigin: false },
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
