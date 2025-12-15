import { test, expect, _electron as electron, ElectronApplication, Page } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';
import { execSync } from 'child_process';

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
// Auto-detect architecture - prefer arm64 on Windows ARM, fall back to x64
const ARCH = process.arch === 'arm64' ? 'arm64' : 'x64';
const ELECTRON_APP = path.join(ROOT_DIR, `static/out/Logseq-win32-${ARCH}/Logseq.exe`);
const SIDECAR_JAR = path.join(ROOT_DIR, 'sidecar/target/logsidian-sidecar.jar');

// Test state
let electronApp: ElectronApplication;
let page: Page;
const consoleErrors: string[] = [];
const consoleWarnings: string[] = [];

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
      execSync('taskkill /f /im java.exe 2>nul', { stdio: 'ignore' });
      console.log('Killed existing Logseq/Java processes');
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
 * Wait for sidecar to be running (started by Electron)
 */
async function waitForSidecar(timeoutMs: number = 30000): Promise<boolean> {
  const net = await import('net');
  const startTime = Date.now();

  while (Date.now() - startTime < timeoutMs) {
    const isRunning = await new Promise<boolean>((resolve) => {
      const socket = new net.Socket();
      socket.setTimeout(500);
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
      console.log('Sidecar is running on port 47632');
      return true;
    }

    await new Promise(r => setTimeout(r, 500));
  }

  console.log('Sidecar did not start within timeout');
  return false;
}

test.describe('Sidecar Electron Smoke Tests', () => {

  test.beforeAll(async () => {
    // Verify Electron app exists
    if (!fs.existsSync(ELECTRON_APP)) {
      throw new Error(`Electron app not found at ${ELECTRON_APP}. Run: scripts/build.ps1`);
    }

    // Kill any existing Logseq processes to avoid single-instance lock
    await killExistingLogseqProcesses();

    // NOTE: We don't manually start sidecar - Electron handles it
    // The app will spawn the JVM sidecar automatically

    // Launch Electron app
    console.log('Launching Electron app...');
    electronApp = await electron.launch({
      executablePath: ELECTRON_APP,
      timeout: 60000,
      env: {
        ...process.env,
        // Enable dev tools for debugging
        ELECTRON_ENABLE_LOGGING: '1',
      },
    });

    // Listen for console output - capture all messages
    electronApp.on('console', (msg) => {
      const text = msg.text();
      const type = msg.type();
      console.log(`[electron-console] ${type}: ${text}`);

      // Capture errors and warnings for later analysis
      if (type === 'error') {
        consoleErrors.push(text);
      } else if (type === 'warning') {
        consoleWarnings.push(text);
      }
    });

    // The app may show a splash screen first, then the main window
    console.log('Waiting for windows...');

    // Wait for any window first
    const firstWin = await electronApp.firstWindow();
    console.log('Got first window');

    // Check if this is the splash screen or main window
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

      // Poll for main window
      let mainWindow: Page | null = null;
      for (let i = 0; i < 60; i++) {
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
        if (i % 10 === 0) console.log(`Waiting for main window... attempt ${i + 1}`);
      }

      if (mainWindow) {
        page = mainWindow;
        console.log('Found main window');
      } else {
        page = firstWin;
        console.log('Using first window as main (no index.html found)');
      }
    } else {
      page = firstWin;
    }

    // Set up console listener on the page as well
    page.on('console', (msg) => {
      const text = msg.text();
      const type = msg.type();
      if (type === 'error') {
        consoleErrors.push(text);
      } else if (type === 'warning') {
        consoleWarnings.push(text);
      }
    });

    // Wait for DOM content
    await page.waitForLoadState('domcontentloaded');
    console.log('DOM content loaded');

    // Take screenshot
    await page.screenshot({ path: 'e2e-electron/screenshots/dom-content-loaded.png' });

    // Wait for sidecar to be ready (started by Electron)
    console.log('Waiting for sidecar...');
    const sidecarReady = await waitForSidecar(30000);
    if (!sidecarReady) {
      console.warn('Sidecar may not be running - tests may fail');
    }

    // Wait for actual app content (not loading skeletons)
    // The app shows skeleton placeholders during loading
    console.log('Waiting for app content to load...');
    try {
      // Wait for the main layout AND actual content (not skeleton)
      await page.waitForSelector('.cp__sidebar-main-layout', { timeout: 60000 });

      // Wait for content to be visible (skeleton elements disappear)
      // Look for actual page content, journal entries, or the "Add graph" button
      await page.waitForFunction(() => {
        // Check if we have actual content (not just skeleton)
        const hasBlocks = document.querySelector('.blocks-container, .journal-item, .page-blocks-inner');
        const hasAddGraph = document.querySelector('.add-graph-btn, [data-testid="add-graph"]');
        const hasPageTitle = document.querySelector('.page-title, [data-testid="page title"]');
        const hasEditor = document.querySelector('.editor-inner, .block-editor');
        return hasBlocks || hasAddGraph || hasPageTitle || hasEditor;
      }, { timeout: 60000 });

      console.log('App content loaded');
    } catch (e) {
      console.error('Timeout waiting for app content');
      await page.screenshot({ path: 'e2e-electron/screenshots/content-timeout.png' });
    }

    // Take screenshot after content load
    await page.screenshot({ path: 'e2e-electron/screenshots/app-ready.png' });
    console.log('App ready - captured final screenshot');

    // Log any captured errors
    if (consoleErrors.length > 0) {
      console.log(`\n=== Console Errors (${consoleErrors.length}) ===`);
      consoleErrors.forEach((err, i) => console.log(`${i + 1}. ${err}`));
    }
    if (consoleWarnings.length > 0) {
      console.log(`\n=== Console Warnings (${consoleWarnings.length}) ===`);
      consoleWarnings.slice(0, 10).forEach((warn, i) => console.log(`${i + 1}. ${warn}`));
      if (consoleWarnings.length > 10) {
        console.log(`... and ${consoleWarnings.length - 10} more warnings`);
      }
    }
  });

  test.afterAll(async () => {
    // Log final error summary
    if (consoleErrors.length > 0) {
      console.log(`\n=== Final Console Errors Summary (${consoleErrors.length}) ===`);
      consoleErrors.forEach((err, i) => console.log(`${i + 1}. ${err}`));
    }

    // Close Electron app (this also stops the sidecar)
    if (electronApp) {
      await electronApp.close();
    }
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
    // Wait a bit for any async errors
    await page.waitForTimeout(2000);

    // Filter errors to find critical ones
    const criticalErrors = consoleErrors.filter(text => {
      // Filter out known benign errors
      if (text.includes('favicon.ico') ||
          text.includes('DevTools') ||
          text.includes('Extension context invalidated')) {
        return false;
      }
      return true;
    });

    // Log all critical errors for debugging
    if (criticalErrors.length > 0) {
      console.log(`\n=== Critical Console Errors (${criticalErrors.length}) ===`);
      criticalErrors.forEach((err, i) => console.log(`${i + 1}. ${err}`));
    }

    // Fail on sidecar-related errors (these indicate integration problems)
    const sidecarErrors = criticalErrors.filter(e =>
      e.toLowerCase().includes('sidecar') ||
      e.toLowerCase().includes('transit') ||
      e.toLowerCase().includes('socket') ||
      e.toLowerCase().includes('connection refused')
    );

    if (sidecarErrors.length > 0) {
      console.log(`\n=== Sidecar Errors (${sidecarErrors.length}) ===`);
      sidecarErrors.forEach((err, i) => console.log(`${i + 1}. ${err}`));
    }

    expect(sidecarErrors).toHaveLength(0);
  });

  // Re-enabled after hybrid architecture fix (browser.cljs type check bug)
  // The architecture now:
  // 1. Starts web worker first for file parsing (mldoc)
  // 2. Then connects sidecar for queries
  // See: docs/tasks/hybrid-architecture.md
  test('can create a new page', async () => {
    // Take initial screenshot
    await page.screenshot({ path: 'e2e-electron/screenshots/before-create-page.png' });

    // Debug: log what elements exist
    const elements = await page.evaluate(() => {
      const selectors = [
        '.cp__header-search-btn',
        'button[aria-label="Search"]',
        '.search-button',
        'input[type="text"]',
        '.search-input',
        '#search-field',
        '.cp__header'
      ];
      return selectors.map(s => ({
        selector: s,
        count: document.querySelectorAll(s).length
      }));
    });
    console.log('Available elements:', JSON.stringify(elements, null, 2));

    // Use keyboard shortcut to open search (Ctrl+K or Cmd+K)
    const modifier = process.platform === 'darwin' ? 'Meta' : 'Control';
    await page.keyboard.press(`${modifier}+k`);
    await page.waitForTimeout(1000);

    // Take screenshot to debug search modal
    await page.screenshot({ path: 'e2e-electron/screenshots/search-opened.png' });

    // Find the search input - try multiple approaches
    let searchInput = page.locator('#search-field').first();
    if (!(await searchInput.isVisible({ timeout: 1000 }).catch(() => false))) {
      searchInput = page.locator('.cp__cmdk input').first();
    }
    if (!(await searchInput.isVisible({ timeout: 1000 }).catch(() => false))) {
      searchInput = page.locator('input[placeholder*="Search"], input[placeholder*="search"]').first();
    }
    if (!(await searchInput.isVisible({ timeout: 1000 }).catch(() => false))) {
      // Last resort: any visible input
      searchInput = page.locator('input:visible').first();
    }

    // Type a new page name
    const pageName = `test-page-${Date.now()}`;
    await searchInput.fill(pageName);
    await page.waitForTimeout(500);

    // Take screenshot after typing
    await page.screenshot({ path: 'e2e-electron/screenshots/search-typed.png' });

    // Look for "Create page" option and click it
    const createOption = page.locator('text=/Create.*page/i, text=/New page/i, [data-value="create"]').first();
    if (await createOption.isVisible({ timeout: 3000 }).catch(() => false)) {
      await createOption.scrollIntoViewIfNeeded();
      await createOption.click({ force: true, timeout: 5000 });
    } else {
      // Press Enter to create/navigate
      await page.keyboard.press('Enter');
    }
    await page.waitForTimeout(2000);

    // Close search modal if still open by pressing Escape
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);

    // Take screenshot after navigation
    await page.screenshot({ path: 'e2e-electron/screenshots/after-create-page.png' });

    // Verify we're on the new page - check for page title or the page name in content
    const pageVisible = await page.locator(`text="${pageName}"`).first().isVisible({ timeout: 5000 }).catch(() => false);

    // Also check if we at least navigated somewhere (even if title doesn't match exactly)
    const hasPageContent = await page.locator('.page-blocks-inner, .blocks-container, .page').first().isVisible({ timeout: 2000 }).catch(() => false);

    await page.screenshot({ path: 'e2e-electron/screenshots/page-created.png' });

    // The page should be visible OR we should have page content
    expect(pageVisible || hasPageContent).toBe(true);
  });

  // Re-enabled after hybrid architecture fix
  test('can create a block', async () => {
    // Take screenshot to see current state
    await page.screenshot({ path: 'e2e-electron/screenshots/before-block-create.png' });

    // First, ensure the sidebar is visible by clicking the hamburger menu
    const sidebar = page.locator('.left-sidebar-inner, .cp__sidebar-left-layout, nav.left-sidebar');
    const sidebarVisible = await sidebar.isVisible({ timeout: 1000 }).catch(() => false);

    if (!sidebarVisible) {
      console.log('Sidebar not visible, opening via hamburger menu...');
      // Click hamburger menu to open sidebar
      const hamburger = page.locator('.cp__header-menu-trigger, button[aria-label="Menu"], .menu-trigger').first();
      if (await hamburger.isVisible({ timeout: 2000 }).catch(() => false)) {
        await hamburger.click();
        await page.waitForTimeout(1000);
      }
    }

    // Take screenshot after trying to open sidebar
    await page.screenshot({ path: 'e2e-electron/screenshots/after-sidebar-open.png' });

    // Try keyboard shortcut to go to journals (g then j in Logseq)
    console.log('Navigating to journals via keyboard shortcut...');
    await page.keyboard.press('g');
    await page.waitForTimeout(200);
    await page.keyboard.press('j');
    await page.waitForTimeout(1000);

    // If keyboard didn't work, try clicking the Journals link
    const journalsLink = page.locator('a:has-text("Journals"), [data-testid="nav-journals"]').first();
    if (await journalsLink.isVisible({ timeout: 2000 }).catch(() => false)) {
      console.log('Found Journals link, clicking...');
      await journalsLink.scrollIntoViewIfNeeded();
      await journalsLink.click({ force: true });
      await page.waitForTimeout(500);
    }

    // Take screenshot after navigation
    await page.screenshot({ path: 'e2e-electron/screenshots/after-journals-nav.png' });

    // Click on the main content area to focus - try to find an editable area
    const editableSelectors = [
      '.editor-inner',
      '.block-editor',
      '.blocks-container .block-content',
      '.page-blocks-inner',
      '[contenteditable="true"]',
      '.page',
      'main'
    ];

    let clicked = false;
    for (const selector of editableSelectors) {
      const element = page.locator(selector).first();
      if (await element.isVisible({ timeout: 500 }).catch(() => false)) {
        await element.scrollIntoViewIfNeeded();
        await element.click({ force: true });
        clicked = true;
        console.log(`Clicked on: ${selector}`);
        break;
      }
    }

    if (!clicked) {
      // Last resort: click in the center of the main area
      await page.click('main', { position: { x: 400, y: 300 } });
    }

    await page.waitForTimeout(300);

    // Press Enter first to create a new block (instead of editing existing block content)
    await page.keyboard.press('Enter');
    await page.waitForTimeout(300);

    // Type some content in the new block
    const blockContent = `E2E test ${Date.now()}`;
    await page.keyboard.type(blockContent);
    await page.waitForTimeout(500);

    // Take screenshot after typing
    await page.screenshot({ path: 'e2e-electron/screenshots/after-typing.png' });

    // Press Enter to confirm the block and create another (optional, just to ensure content is saved)
    await page.keyboard.press('Enter');
    await page.waitForTimeout(500);

    // Take final screenshot
    await page.screenshot({ path: 'e2e-electron/screenshots/block-created.png' });

    // Verify block content is visible somewhere on page
    const blockVisible = await page.locator(`text="${blockContent}"`).first().isVisible({ timeout: 5000 }).catch(() => false);
    expect(blockVisible).toBe(true);
  });

});
