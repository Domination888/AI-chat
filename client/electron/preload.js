const { contextBridge, ipcRenderer } = require('electron');

// 在 window 对象上暴露 API
contextBridge.exposeInMainWorld('electronAPI', {
  getAppVersion: () => ipcRenderer.invoke('get-app-version'),

  // Live2D 控制：主窗口 → 子窗口
  live2dControl: (action, data) => ipcRenderer.send('live2d-control', action, data),

  // 切换 Live2D 窗口置顶
  live2dToggleTop: () => ipcRenderer.invoke('live2d-toggle-top'),

  // 监听 Live2D 子窗口的点击互动事件
  onLive2dInteract: (callback) => {
    ipcRenderer.on('live2d-interact-event', () => callback());
  },
});