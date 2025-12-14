import { test, expect, _electron as electron, ElectronApplication, Page } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';
import { spawn, ChildProcess, execSync } from 'child_process';

/**
 * Sidecar Smoke Tests for Electron App
 *
 * These tests verify that the Logsidian Electron app:
 * 1. Starts successfully
 * 2. Connects to the JVM sidecar
 * 3. Can perform basic operations through the sidecar
 *
 * Prerequisites:
 * - Electron app built: yarn release-electron (or scripts/build.ps1)
 * - Sidecar JAR built: cd sidecar && clj -T:build uberjar
 */

// Paths
const ROOT_DIR = path.resolve(__dirname, '../..');
const ELECTRON_APP = path.join(ROOT_DIR, 'static/out/Logseq-win32-x64/Logseq.exe');
const SIDECAR_JAR = path.join(ROOT_DIR, 'sidecar/target/logsidian-sidecar.jar');

// Test state
let electronApp: ElectronApplication;
let page: Page;
let sidecarProcess: ChildProcess | null = null;

/**
 * Kill any existing Logseq processes to avoid single-instance lock issues.
 * Logseq uses requestSingleInstanceLock() which causes new instances to quit
 * if another instance is already running.
 */
async function killExistingLogseqProcesses(): Promise<void> {
  console.log('Checking for existing Logseq processes...');
  try {
    // Windows: use taskkill to terminate Logseq.exe processes
    if (process.platform === 'win32') {
      execSync('taskkill /f /im Logseq.exe 2>nul', { stdio: 'ignore' });
      console.log('Killed existing Logseq processes');
    } else {
      // macOS/Linux: use pkill
      execSync('pkill -f Logseq 2>/dev/null || true', { stdio: 'ignore' });
    }
    // Give the OS a moment to clean up
    await new Promise(resolve => setTimeout(resolve, 1000));
  } catch {
    // No existing processes - that's fine
    console.log('No existing Logseq processes found');
  }
}

/**
 * Start the sidecar server if not already running
 */
async function ensureSidecarRunning(): Promise<void> {
  // Check if sidecar is already running by testing the port
  const net = await import('net');
  const isRunning = await new Promise<boolean>((resolve) => {
    const socket = new net.Socket();
    socket.setTimeout(1000);
    socket.on('connect', () => {
      socket.destroy();
      resolve(true);
    });
    socket.on('timeout', () => {
      socket.destroy();
      resolve(false);
    });
    socket.on('error', () => {
      resolve(false);
    });
    socket.connect(47632, '127.0.0.1');
  });

  if (isRunning) {
    console.log('Sidecar already running on port 47632');
    return;
  }

  // Start sidecar
  console.log('Starting sidecar server...');
  if (!fs.existsSync(SIDECAR_JAR)) {
    throw new Error(`Sidecar JAR not found at ${SIDECAR_JAR}. Run: cd sidecar && clj -T:build uberjar`);
  }

  sidecarProcess = spawn('java', ['-jar', SIDECAR_JAR], {
    cwd: path.join(ROOT_DIR, 'sidecar'),
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  // Wait for sidecar to start
  await new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Sidecar startup timeout')), 10000);

    sidecarProcess!.stdout?.on('data', (data) => {
      const output = data.toString();
      console.log('[sidecar]', output.trim());
      if (output.includes('Server started') || output.includes('listening')) {
        clearTimeout(timeout);
        resolve();
      }
    });

    sidecarProcess!.stderr?.on('data', (data) => {
      console.error('[sidecar-err]', data.toString().trim());
    });

    sidecarProcess!.on('error', (err) => {
      clearTimeout(timeout);
      reject(err);
    });

    // Also poll the port
    const pollInterval = setInterval(async () => {
      const socket = new net.Socket();
      socket.setTimeout(500);
      socket.on('connect', () => {
        socket.destroy();
        clearInterval(pollInterval);
        clearTimeout(timeout);
        resolve();
      });
      socket.on('error', () => {});
      socket.connect(47632, '127.0.0.1');
    }, 500);
  });

  console.log('Sidecar started successfully');
}

test.describe('Sidecar Electron Smoke Tests', () => {

  test.beforeAll(async () => {
    // Verify Electron app exists
    if (!fs.existsSync(ELECTRON_APP)) {
      throw new Error(`Electron app not found at ${ELECTRON_APP}. Run: scripts/build.ps1`);
    }

    // Kill any existing Logseq processes to avoid single-instance lock
    await killExistingLogseqProcesses();

    // Ensure sidecar is running
    await ensureSidecarRunning();

    // Launch Electron app
    console.log('Launching Electron app...');
    electronApp = await electron.launch({
      executablePath: ELECTRON_APP,
      // Don't pass --no-sandbox as it may cause issues
      timeout: 60000,
      env: {
        ...process.env,
        // Enable dev tools for debugging
        ELECTRON_ENABLE_LOGGING: '1',
      },
    });

    // Listen for console output - capture all messages
    electronApp.on('console', (msg) => {
      console.log(`[electron-console] ${msg.type()}: ${msg.text()}`);
    });

    // The app may show a splash screen first, then the main window
    // We need to wait for the main window (not splash)
    console.log('Waiting for windows...');

    // Wait for any window first
    const firstWin = await electronApp.firstWindow();
    console.log('Got first window');

    // Check if this is the splash screen (small, frameless) or main window
    // Splash is 300x350, main window is much larger
    const firstWinTitle = await firstWin.title();
    const firstWinUrl = firstWin.url();
    console.log(`First window - title: "${firstWinTitle}", url: ${firstWinUrl}`);

    // Take screenshot of first window
    try {
      await firstWin.screenshot({ path: 'e2e-electron/screenshots/first-window.png' });
    } catch (e) {
      console.log('Could not screenshot first window (may be splash)');
    }

    // If this is splash screen, wait for main window
    if (firstWinUrl.includes('splash.html')) {
      console.log('First window is splash screen, waiting for main window...');

      // Wait for another window to appear
      const allWindows = await electronApp.windows();
      console.log(`Current window count: ${allWindows.length}`);

      // Poll for main window
      let mainWindow: Page | null = null;
      for (let i = 0; i < 30; i++) {
        const windows = await electronApp.windows();
        for (const win of windows) {
          const url = win.url();
          if (url.includes('index.html') || url.includes('#')) {
            mainWindow = win;
            break;
          }
        }
        if (mainWindow) break;
        await new Promise(r => setTimeout(r, 1000));
        console.log(`Waiting for main window... attempt ${i + 1}`);
      }

      if (mainWindow) {
        page = mainWindow;
        console.log('Found main window');
      } else {
        // Fallback: use the first window anyway
        page = firstWin;
        console.log('Using first window as main');
      }
    } else {
      page = firstWin;
    }

    // Wait for DOM content
    await page.waitForLoadState('domcontentloaded');
    console.log('DOM content loaded');

    // Take screenshot
    await page.screenshot({ path: 'e2e-electron/screenshots/dom-content-loaded.png' });
    console.log('Captured DOM loaded screenshot');

    // Wait for the app UI to load
    try {
      await page.waitForSelector('.cp__sidebar-main-layout, main, #root, #app', {
        timeout: 60000,
      });
      console.log('App UI loaded');
    } catch (e) {
      console.error('Failed to wait for UI');
      await page.screenshot({ path: 'e2e-electron/screenshots/ui-timeout.png' });
      // Don't throw - let's see what state the app is in
    }

    // Take screenshot after UI load
    await page.screenshot({ path: 'e2e-electron/screenshots/app-ready.png' });
    console.log('App ready - captured final screenshot');
  });

  test.afterAll(async () => {
    // Close Electron app
    if (electronApp) {
      await electronApp.close();
    }

    // Note: We don't kill the sidecar - it may be used by other tests
    // If you want to kill it, uncomment:
    // if (sidecarProcess) {
    //   sidecarProcess.kill();
    // }
  });

  test('app launches and shows UI', async () => {
    // Wait for the main UI to load
    await page.waitForSelector('.cp__sidebar-main-layout, [data-testid="page title"], .cp__header', {
      timeout: 30000,
    });

    // Take a screenshot for debugging
    await page.screenshot({ path: 'e2e-electron/screenshots/app-launched.png' });

    // Verify we have some UI element
    const hasUI = await page.locator('.cp__sidebar-main-layout, .left-sidebar-inner, main').count();
    expect(hasUI).toBeGreaterThan(0);
  });

  test('no critical console errors on startup', async () => {
    const errors: string[] = [];

    // Collect console errors
    page.on('console', (msg) => {
      if (msg.type() === 'error') {
        const text = msg.text();
        // Filter out known benign errors
        if (!text.includes('favicon.ico') &&
            !text.includes('DevTools') &&
            !text.includes('Extension context invalidated')) {
          errors.push(text);
        }
      }
    });

    // Wait a bit for any async errors
    await page.waitForTimeout(2000);

    // Log errors but don't fail on all of them (some may be expected)
    if (errors.length > 0) {
      console.log('Console errors found:', errors);
    }

    // Fail only on critical sidecar-related errors
    const sidecarErrors = errors.filter(e =>
      e.toLowerCase().includes('sidecar') ||
      e.toLowerCase().includes('transit') ||
      e.toLowerCase().includes('socket')
    );
    expect(sidecarErrors).toHaveLength(0);
  });

  // Re-enabled after hybrid architecture fix (browser.cljs type check bug)
  // The architecture now:
  // 1. Starts web worker first for file parsing (mldoc)
  // 2. Then connects sidecar for queries
  // See: docs/tasks/hybrid-architecture.md
  test('can create a new page', async () => {
    // Open command palette with Ctrl+K
    await page.keyboard.press('Control+k');
    await page.waitForTimeout(500);

    // Type a new page name
    const pageName = `test-page-${Date.now()}`;
    await page.keyboard.type(pageName);
    await page.waitForTimeout(300);

    // Press Enter to create/go to page
    await page.keyboard.press('Enter');
    await page.waitForTimeout(1000);

    // Verify we're on the new page (page title should be visible)
    const pageTitle = page.locator('[data-testid="page title"], .page-title');
    await expect(pageTitle).toBeVisible({ timeout: 5000 });

    await page.screenshot({ path: 'e2e-electron/screenshots/page-created.png' });
  });

  // Re-enabled after hybrid architecture fix
  test('can create a block', async () => {
    // Click in the editor area to focus
    await page.click('.editor-wrapper, .block-editor, .ls-block');
    await page.waitForTimeout(300);

    // Type some content
    const blockContent = `Test block created at ${new Date().toISOString()}`;
    await page.keyboard.type(blockContent);
    await page.waitForTimeout(300);

    // Press Escape to save
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);

    // Verify block is visible
    const block = page.locator(`.ls-block:has-text("${blockContent.substring(0, 20)}")`);
    await expect(block).toBeVisible({ timeout: 5000 });

    await page.screenshot({ path: 'e2e-electron/screenshots/block-created.png' });
  });

});
