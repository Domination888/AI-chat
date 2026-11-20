const {contextBridge,ipcRenderer}=require('electron')
contextBridge.exposeInMainWorld('storyDesktop',{
 platform:process.platform,
 openSource:url=>ipcRenderer.invoke('story-open-source',url),
 characterPack:()=>ipcRenderer.invoke('story-character-pack'),
 retryLocalService:kind=>ipcRenderer.invoke('story-retry-local-service',kind),
 bootstrap:()=>ipcRenderer.invoke('story-bootstrap'),
 setServer:url=>ipcRenderer.invoke('story-server',url),
 saveSession:token=>ipcRenderer.invoke('story-save-session',token),
 refreshSession:()=>ipcRenderer.invoke('story-refresh'),
 logout:()=>ipcRenderer.invoke('story-logout'),
 installModelPack:()=>ipcRenderer.invoke('story-install-models'),
})
