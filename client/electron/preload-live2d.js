const { contextBridge, ipcRenderer } = require('electron');

// Live2D 子窗口的 preload
contextBridge.exposeInMainWorld('live2dAPI', {
  // 拖动窗口：发送屏幕绝对坐标
  moveWindow: (x, y) => ipcRenderer.send('live2d-move', { x, y }),

  // 监听主窗口发来的 Live2D 控制动作
  onAction: (callback) => {
    ipcRenderer.on('live2d-action', (_event, action, data) => callback(action, data));
  },

  // 点击互动：通知主窗口用户点击了模型
  sendInteract: () => ipcRenderer.send('live2d-interact'),

  // 切换置顶
  toggleTop: () => ipcRenderer.invoke('live2d-toggle-top'),
});