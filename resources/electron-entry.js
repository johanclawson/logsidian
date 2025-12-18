// Enable Node.js 22 built-in compile cache (supports ESM)
// This caches compiled JavaScript bytecode to avoid re-parsing on subsequent launches
// Impact: 30-50% faster startup time
const Module = require('module');
if (Module.enableCompileCache) {
  Module.enableCompileCache();
}

// ============================================================================
// EARLY SPLASH SCREEN
// Show splash BEFORE loading heavy ClojureScript bundle for fastest perceived startup
// ============================================================================
const { app, BrowserWindow } = require('electron');
const path = require('path');

let splashWin = null;

function createSplash() {
  // Skip in development mode
  if (process.env.NODE_ENV === 'development' ||
      process.argv.includes('--dev') ||
      process.argv.some(arg => arg.includes('localhost'))) {
    return null;
  }

  splashWin = new BrowserWindow({
    width: 300,
    height: 350,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    skipTaskbar: true,
    resizable: false,
    center: true,
    show: true,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true
    }
  });
  splashWin.loadFile(path.join(__dirname, 'splash.html'));
  return splashWin;
}

function closeSplash() {
  if (splashWin && !splashWin.isDestroyed()) {
    splashWin.close();
    splashWin = null;
  }
}

// Export splash API for ClojureScript to close later
global.__logsidian_splash = {
  close: closeSplash,
  isOpen: () => splashWin !== null && !splashWin.isDestroyed()
};

// Show splash as early as possible - either now (if ready) or on ready event
if (app.isReady()) {
  createSplash();
} else {
  app.once('ready', createSplash);
}

// Ensure splash is closed when app quits (prevents orphaned splash window)
app.on('before-quit', closeSplash);
app.on('will-quit', closeSplash);
app.on('window-all-closed', closeSplash);

// ============================================================================
// MAIN APPLICATION
// Load the ClojureScript bundle (this is the slow part)
// ============================================================================
require('./electron.js');
