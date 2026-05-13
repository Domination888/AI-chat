const { contextBridge, ipcRenderer } = require('electron');

// 在window对象上暴露API
contextBridge.exposeInMainWorld('electronAPI', {
  getAppVersion: () => ipcRenderer.invoke('get-app-version'),
});