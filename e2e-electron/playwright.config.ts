import { defineConfig } from '@playwright/test';

/**
 * Playwright configuration for Electron E2E tests.
 *
 * These tests launch the actual Electron app and verify:
 * - App starts correctly
 * - Sidecar connects and communicates
 * - Basic operations work through sidecar backend
 *
 * Usage:
 *   npx playwright test --config e2e-electron/playwright.config.ts
 */
export default defineConfig({
  testDir: './tests',
  timeout: 60000,  // 60s per test (Electron startup can be slow)
  retries: 0,
  workers: 1,  // Run tests serially - Electron doesn't support parallel
  reporter: [
    ['html', { outputFolder: '../clj-e2e/error-reports/playwright-electron' }],
    ['list']
  ],
  use: {
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
});
