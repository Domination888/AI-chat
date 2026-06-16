const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  getAppVersion: () => ipcRenderer.invoke('get-app-version'),
  isPackaged: () => ipcRenderer.invoke('is-packaged'),
  isFirstRun: () => ipcRenderer.invoke('is-first-run'),
  completeSetup: () => ipcRenderer.invoke('complete-setup'),
  openLogsDir: () => ipcRenderer.invoke('open-logs-dir'),
  listLogs: () => ipcRenderer.invoke('list-logs'),
  readLog: (id, maxLines) => ipcRenderer.invoke('read-log', { id, maxLines }),
  restartServices: () => ipcRenderer.invoke('restart-services'),
  getApiBase: () => ipcRenderer.invoke('get-api-base'),
  onShowSetupWizard: (callback) => {
    ipcRenderer.on('show-setup-wizard', () => callback());
  },
  live2dControl: (action, data) => ipcRenderer.send('live2d-control', action, data),
  live2dToggleTop: () => ipcRenderer.invoke('live2d-toggle-top'),
  onLive2dInteract: (callback) => {
    ipcRenderer.on('live2d-interact-event', () => callback());
  },
});
