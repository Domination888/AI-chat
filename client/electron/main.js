const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');

// 保持对window对象的全局引用，避免被垃圾回收
let mainWindow;

function createWindow() {
  // 创建浏览器窗口
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js')
    }
  });

  // 加载应用 - 使用开发服务器
  mainWindow.loadURL('http://localhost:3000');
  mainWindow.webContents.openDevTools();

  // 当窗口关闭时触发
  mainWindow.on('closed', function () {
    mainWindow = null;
  });
}

// 当Electron完成初始化并准备创建浏览器窗口时调用此方法
app.whenReady().then(createWindow);

// 当所有窗口都被关闭时触发
app.on('window-all-closed', function () {
  // 在macOS上，除非用户用Cmd + Q显式退出，否则应用及其菜单栏会保持活动状态
  if (process.platform !== 'darwin') app.quit();
});

app.on('activate', function () {
  // 在macOS上，当点击dock图标并且没有其他窗口打开时，通常会在应用程序中重新创建一个窗口
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});

// IPC通信处理
ipcMain.handle('get-app-version', () => {
  return app.getVersion();
});