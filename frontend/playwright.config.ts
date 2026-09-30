import { defineConfig, devices } from '@playwright/test';

// End-to-end tests run against the real service (API_URL, default :8080) through
// the Vite dev proxy on VITE_PORT (default 5173), main's ports (FE24). Start the service first
// (docker compose up --build in the repo root).
const devPort = Number(process.env.VITE_PORT ?? 5173);
const baseURL = `http://localhost:${devPort}`;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile-375', use: { ...devices['Desktop Chrome'], viewport: { width: 375, height: 667 } } },
  ],
  webServer: {
    command: 'npm run dev',
    url: baseURL,
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
