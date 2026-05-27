const { app, BrowserWindow, ipcMain, screen } = require('electron');
const path = require('path');

// 保持对 window 对象的全局引用，避免被垃圾回收
let mainWindow;
let live2dWindow;

const VITE_DEV_URL = 'http://localhost:3000';

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js')
    }
  });

  mainWindow.loadURL(VITE_DEV_URL);
  mainWindow.webContents.openDevTools();

  mainWindow.on('closed', function () {
    mainWindow = null;
    // 主窗口关闭时同时关闭 Live2D 窗口
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
    transparent: true,       // 透明窗口
    frame: false,            // 无边框
    resizable: false,        // 不可缩放
    alwaysOnTop: true,       // 始终在桌面最前
    skipTaskbar: true,       // 不在任务栏显示
    hasShadow: false,        // 无阴影
    focusable: false,        // 不抢焦点
    backgroundColor: '#00000000',  // 完全透明背景
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload-live2d.js')
    }
  });

  // 加载同一个 Vite 页面，通过 query 参数进入 Live2D 模式
  live2dWindow.loadURL(VITE_DEV_URL + '?mode=live2d');

  // 隐藏菜单栏
  live2dWindow.setMenuBarVisibility(false);

  live2dWindow.on('closed', function () {
    live2dWindow = null;
  });
}

// ============================================================
// IPC 通信：主窗口 → Live2D 子窗口
// ============================================================

// 转发 Live2D 控制消息到子窗口
ipcMain.on('live2d-control', (_event, action, data) => {
  if (live2dWindow && !live2dWindow.isDestroyed()) {
    live2dWindow.webContents.send('live2d-action', action, data);
  }
});

// Live2D 子窗口拖动：接收绝对坐标，直接设置窗口位置（避免 delta 累积误差导致的抽搐）
ipcMain.on('live2d-move', (_event, { x, y }) => {
  if (live2dWindow && !live2dWindow.isDestroyed()) {
    live2dWindow.setPosition(Math.round(x), Math.round(y));
  }
});

// Live2D 子窗口点击互动：转发到主窗口
ipcMain.on('live2d-interact', () => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('live2d-interact-event');
  }
});

// 切换 Live2D 窗口置顶
ipcMain.handle('live2d-toggle-top', () => {
  if (live2dWindow && !live2dWindow.isDestroyed()) {
    const isOnTop = live2dWindow.isAlwaysOnTop();
    live2dWindow.setAlwaysOnTop(!isOnTop);
    return !isOnTop;
  }
  return false;
});

// ============================================================
// App 生命周期
// ============================================================

app.whenReady().then(() => {
  createMainWindow();
  createLive2DWindow();
});

app.on('window-all-closed', function () {
  if (process.platform !== 'darwin') app.quit();
});

app.on('activate', function () {
  if (BrowserWindow.getAllWindows().length === 0) {
    createMainWindow();
    createLive2DWindow();
  }
});

ipcMain.handle('get-app-version', () => {
  return app.getVersion();
});