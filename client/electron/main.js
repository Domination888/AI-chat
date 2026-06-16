const {
  app,
  BrowserWindow,
  ipcMain,
  protocol,
  net,
  session,
  screen,
  Tray,
  Menu,
  nativeImage,
  shell,
} = require('electron');
const path = require('path');
const { pathToFileURL } = require('url');
const serviceManager = require('./service-manager');
const { listLogSources, readLogTail } = require('./logs-reader');
const { isFirstRun, markSetupComplete, isPackaged, getLogsRoot } = require('./paths');

let mainWindow;
let live2dWindow;
let splashWindow;
let tray;
let quitting = false;

const VITE_DEV_URL = 'http://localhost:3000';
const isDev = !app.isPackaged;
const DIST_DIR = path.join(__dirname, '../dist');
const APP_INDEX_URL = 'app://./index.html';

protocol.registerSchemesAsPrivileged([
  {
    scheme: 'app',
    privileges: {
      standard: true,
      secure: true,
      supportFetchAPI: true,
      corsEnabled: true,
      stream: true,
    },
  },
]);

function resolveDistPath(requestUrl) {
  const { pathname } = new URL(requestUrl);
  let relativePath = decodeURIComponent(pathname).replace(/^\/+/, '');
  if (!relativePath || relativePath === '.') {
    relativePath = 'index.html';
  }
  const filePath = path.normalize(path.join(DIST_DIR, relativePath));
  if (!filePath.startsWith(DIST_DIR)) {
    return null;
  }
  return filePath;
}

function registerAppProtocol() {
  protocol.handle('app', (request) => {
    const filePath = resolveDistPath(request.url);
    if (!filePath) {
      return new Response('Forbidden', { status: 403 });
    }
    return net.fetch(pathToFileURL(filePath).toString());
  });
}

/** app:// 页面访问 127.0.0.1 API 时补齐 CORS 响应头（与后端 WebCorsConfig 双保险） */
function allowLocalhostCors() {
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    const url = details.url || '';
    const isLocalApi =
      url.startsWith('http://127.0.0.1') || url.startsWith('http://localhost');
    if (!isLocalApi) {
      callback({ responseHeaders: details.responseHeaders });
      return;
    }
    const headers = { ...details.responseHeaders };
    headers['Access-Control-Allow-Origin'] = ['*'];
    headers['Access-Control-Allow-Headers'] = ['*'];
    headers['Access-Control-Allow-Methods'] = ['GET, POST, PUT, DELETE, PATCH, OPTIONS'];
    callback({ responseHeaders: headers });
  });
}

function loadProductionPage(webContents, query) {
  const url = query ? `${APP_INDEX_URL}?${query}` : APP_INDEX_URL;
  return webContents.loadURL(url);
}

function createSplashWindow() {
  splashWindow = new BrowserWindow({
    width: 420,
    height: 220,
    frame: false,
    resizable: false,
    alwaysOnTop: true,
    show: false,
    webPreferences: { nodeIntegration: true, contextIsolation: false },
  });
  splashWindow.loadFile(path.join(__dirname, 'splash.html'));
  splashWindow.once('ready-to-show', () => splashWindow?.show());
}

function updateSplash(status) {
  if (splashWindow && !splashWindow.isDestroyed()) {
    splashWindow.webContents.send('splash-status', status);
  }
}

function closeSplash() {
  if (splashWindow && !splashWindow.isDestroyed()) {
    splashWindow.close();
  }
  splashWindow = null;
}

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    show: false,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js'),
    },
  });

  if (isDev) {
    mainWindow.loadURL(VITE_DEV_URL);
    mainWindow.webContents.openDevTools();
  } else {
    loadProductionPage(mainWindow.webContents);
  }

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
    if (isFirstRun()) {
      mainWindow.webContents.send('show-setup-wizard');
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
    if (live2dWindow && !live2dWindow.isDestroyed()) {
      live2dWindow.close();
    }
  });
}

function createLive2DWindow() {
  const { width: screenWidth, height: screenHeight } = screen.getPrimaryDisplay().workAreaSize;

  live2dWindow = new BrowserWindow({
    width: 350,
    height: 500,
    x: screenWidth - 400,
    y: screenHeight - 550,
    transparent: true,
    frame: false,
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    hasShadow: false,
    focusable: false,
    backgroundColor: '#00000000',
    show: false,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload-live2d.js'),
    },
  });

  if (isDev) {
    live2dWindow.loadURL(`${VITE_DEV_URL}?mode=live2d`);
  } else {
    loadProductionPage(live2dWindow.webContents, 'mode=live2d');
  }

  live2dWindow.setMenuBarVisibility(false);
  live2dWindow.once('ready-to-show', () => live2dWindow?.show());

  live2dWindow.on('closed', () => {
    live2dWindow = null;
  });
}

function createTray() {
  if (tray) return;
  const icon = nativeImage.createEmpty();
  tray = new Tray(icon);
  tray.setToolTip('AI-Chat');
  tray.setContextMenu(
    Menu.buildFromTemplate([
      {
        label: '显示主窗口',
        click: () => mainWindow?.show(),
      },
      {
        label: '重启内置服务',
        click: async () => {
          createSplashWindow();
          try {
            await serviceManager.stopAll();
            await serviceManager.startAll(updateSplash);
          } catch (err) {
            console.error(err);
          } finally {
            closeSplash();
          }
        },
      },
      {
        label: '打开日志目录',
        click: () => shell.openPath(getLogsRoot()),
      },
      { type: 'separator' },
      {
        label: '退出',
        click: () => {
          quitting = true;
          app.quit();
        },
      },
    ])
  );
}

ipcMain.on('live2d-control', (_event, action, data) => {
  if (live2dWindow && !live2dWindow.isDestroyed()) {
    live2dWindow.webContents.send('live2d-action', action, data);
  }
});

ipcMain.on('live2d-move', (_event, { x, y }) => {
  if (live2dWindow && !live2dWindow.isDestroyed()) {
    live2dWindow.setPosition(Math.round(x), Math.round(y));
  }
});

ipcMain.on('live2d-interact', () => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('live2d-interact-event');
  }
});

ipcMain.handle('live2d-toggle-top', () => {
  if (live2dWindow && !live2dWindow.isDestroyed()) {
    const isOnTop = live2dWindow.isAlwaysOnTop();
    live2dWindow.setAlwaysOnTop(!isOnTop);
    return !isOnTop;
  }
  return false;
});

ipcMain.handle('get-app-version', () => app.getVersion());
ipcMain.handle('is-packaged', () => isPackaged());
ipcMain.handle('is-first-run', () => isFirstRun());
ipcMain.handle('complete-setup', () => {
  markSetupComplete();
  return true;
});
ipcMain.handle('open-logs-dir', () => shell.openPath(getLogsRoot()));
ipcMain.handle('list-logs', () => listLogSources());
ipcMain.handle('read-log', (_event, { id, maxLines }) => readLogTail(id, maxLines));
ipcMain.handle('restart-services', async () => {
  await serviceManager.stopAll();
  await serviceManager.startAll(updateSplash);
  return serviceManager.getApiBaseUrl();
});

ipcMain.handle('get-api-base', () => serviceManager.getApiBaseUrl());

app.whenReady().then(async () => {
  if (!isDev) {
    registerAppProtocol();
    allowLocalhostCors();
    createSplashWindow();
    try {
      await serviceManager.startAll(updateSplash);
    } catch (err) {
      console.error(err);
      app.quit();
      return;
    }
    closeSplash();
    createTray();
  }

  createMainWindow();
  createLive2DWindow();
});

app.on('before-quit', async (event) => {
  if (isDev || quitting) return;
  event.preventDefault();
  quitting = true;
  await serviceManager.stopAll();
  app.exit(0);
});

app.on('window-all-closed', async () => {
  if (isDev) {
    if (process.platform !== 'darwin') app.quit();
    return;
  }
  if (!quitting) {
    quitting = true;
    await serviceManager.stopAll();
    app.exit(0);
  }
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createMainWindow();
    createLive2DWindow();
  }
});
