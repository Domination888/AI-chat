const {app,BrowserWindow,ipcMain,protocol,net,session,safeStorage,dialog,shell}=require('electron')
const fs=require('fs'),path=require('path'),crypto=require('crypto')
const {pathToFileURL}=require('url')
const metadata=require('../package.json')
const {assetPath,characterPack}=require('./character-assets.cjs')
const {configurePlatformLocation,storyPermissionAllowed}=require('./platform-location.cjs')
const characterRoot=app.isPackaged?path.join(process.resourcesPath,'personal'):path.join(__dirname,'../../../.local/personal')
const personaRoot=path.join(characterRoot,'personas')
if(process.platform!=='win32')process.umask(0o077)
const mode=app.isPackaged ? metadata.storyDistribution : process.env.STORY_DISTRIBUTION || metadata.storyDistribution || 'offline'
const offline=mode==='offline'
configurePlatformLocation(app.commandLine)
protocol.registerSchemesAsPrivileged([{scheme:'app',privileges:{standard:true,secure:true,supportFetchAPI:true,stream:true}},{scheme:'character',privileges:{standard:true,secure:true,supportFetchAPI:true,corsEnabled:true}}])
app.setName(offline?'幕间离线版':'幕间')
app.setPath('userData',path.join(app.getPath('appData'),offline?'story-space-offline':'story-space-cloud'))
if(!app.isPackaged && process.env.STORY_DEV_DATA)app.setPath('userData',path.resolve(process.env.STORY_DEV_DATA))
let main,runner,base='',localToken='',refreshing=null
const configFile=()=>path.join(app.getPath('userData'),'connection.json')
const tokenFile=()=>path.join(app.getPath('userData'),'session.enc')
function readConfig(){try{return JSON.parse(fs.readFileSync(configFile(),'utf8'))}catch{return {}}}
function validateServer(value){const u=new URL(value);if(u.protocol!=='https:'||u.username||u.password||u.search||u.hash||u.pathname!=='/')throw new Error('服务地址必须是 HTTPS 域名');return u.origin}
function storeRefresh(token){if(!safeStorage.isEncryptionAvailable())throw new Error('系统凭据加密暂不可用');fs.mkdirSync(app.getPath('userData'),{recursive:true});fs.writeFileSync(tokenFile(),safeStorage.encryptString(token),{mode:0o600})}
function refreshToken(){if(!safeStorage.isEncryptionAvailable()||!fs.existsSync(tokenFile()))return '';return safeStorage.decryptString(fs.readFileSync(tokenFile()))}
function clearSession(){fs.rmSync(tokenFile(),{force:true})}
async function remote(endpoint,payload){const r=await fetch(base+endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload),redirect:'error',signal:AbortSignal.timeout(15000)});if(!r.ok)throw new Error('登录已过期，请重新登录');return r.status===204?null:await r.json().catch(()=>null)}
function handle(channel,fn){ipcMain.handle(channel,async(event,...args)=>{if(!main||event.sender!==main.webContents||event.senderFrame!==main.webContents.mainFrame||!event.senderFrame.url.startsWith('app://story/'))throw new Error('Unauthorized IPC sender');return fn(...args)})}
handle('story-character-pack',()=>characterPack(personaRoot))
handle('story-open-source',async value=>{if(typeof value!=='string'||value.length>4096)throw new Error('Invalid source URL');const u=new URL(value);if(u.protocol!=='https:'||u.username||u.password)throw new Error('Invalid source URL');await shell.openExternal(u.href)})
handle('story-bootstrap',()=>({mode,characterAvailable:fs.existsSync(path.join(characterRoot,'live2d/shu/黍.model3.json')),apiBase:base,modelState:runner?.runtimeModelState(),localServices:runner?.serviceStatus(),externalModels:runner?.externalModels,modelError:runner?.modelError,modelProgress:runner?.modelProgress,localToken:offline?localToken:undefined,modelPackInstalled:offline&&fs.existsSync(path.join(app.getPath('userData'),'data/models/manifest.json')),hasSession:!offline&&fs.existsSync(tokenFile())}))
handle('story-server',value=>{if(offline)throw new Error('离线版没有账号服务器');base=validateServer(value);clearSession();fs.mkdirSync(app.getPath('userData'),{recursive:true});fs.writeFileSync(configFile(),JSON.stringify({base}),{mode:0o600})})
handle('story-save-session',token=>{if(offline||typeof token!=='string'||token.length>256)throw new Error('Invalid session');storeRefresh(token)})
handle('story-refresh',()=>{if(offline)return null;if(!refreshing)refreshing=(async()=>{const s=await remote('/api/v1/auth/refresh',{refreshToken:refreshToken()});storeRefresh(s.refreshToken);return {accessToken:s.accessToken,user:s.user,expiresIn:s.expiresIn}})().catch(e=>{clearSession();throw e}).finally(()=>{refreshing=null});return refreshing})
handle('story-logout',async()=>{if(!offline)await remote('/api/v1/auth/logout',{refreshToken:refreshToken()}).catch(()=>{});clearSession()})
handle('story-retry-local-service',async kind=>{if(!offline||!['llm','embedding','voice'].includes(kind))throw new Error('不支持的本地服务');await runner.retryService(kind);return runner.serviceStatus()})
handle('story-install-models',async()=>{if(!offline)throw new Error('Model packs are only available offline');const result=await dialog.showOpenDialog(main,{title:'选择已解压的离线模型包',properties:['openDirectory']});if(result.canceled)return {installed:false};try{await runner.install(result.filePaths[0]);return {installed:true}}finally{base=runner.base;localToken=runner.token}})
app.whenReady().then(async()=>{
  protocol.handle('character',request=>{try{const asset=assetPath(characterRoot,request.url);return new Response(fs.readFileSync(asset.path),{headers:{'Content-Type':asset.type,'Access-Control-Allow-Origin':'*'}})}catch{return new Response('Character asset unavailable',{status:404})}})
  const dist=path.join(__dirname,'..',offline?'dist-offline':'dist-cloud')
  protocol.handle('app',request=>{const u=new URL(request.url);const target=path.resolve(dist,'.'+decodeURIComponent(u.pathname));if(u.host!=='story'||!target.startsWith(dist+path.sep))return new Response('Forbidden',{status:403});return net.fetch(pathToFileURL(target).toString())})
  if(offline){const {OfflineRunner}=require('./offline-runner.cjs');runner=new OfflineRunner({resources:app.isPackaged?path.join(process.resourcesPath,'runtime'):path.join(__dirname,'../../../packaging/release/staging',process.platform),data:path.join(app.getPath('userData'),'data'),...(!app.isPackaged?{java:process.env.STORY_DEV_JAVA,jar:process.env.STORY_DEV_JAR,database:process.env.STORY_DEV_SQLITE_FILE}:{})});await runner.start();base=runner.base;localToken=runner.token}
  else{const devServer=!app.isPackaged && process.env.STORY_DEV_SERVER;if(devServer){const u=new URL(devServer);if(u.protocol!=='http:'||u.hostname!=='127.0.0.1'||u.pathname!=='/'||u.search||u.hash||u.username||u.password)throw new Error('Invalid development server');base=u.origin}else{const saved=readConfig().base || metadata.storyServiceUrl || process.env.STORY_CLOUD_URL;if(saved)base=validateServer(saved)}}
  main=new BrowserWindow({width:1360,height:900,minWidth:720,minHeight:560,backgroundColor:'#f6f4ee',title:offline?'幕间 · 离线版':'幕间 · 商业版用户端',webPreferences:{preload:path.join(__dirname,'release-preload.cjs'),nodeIntegration:false,contextIsolation:true,sandbox:true,webSecurity:true}})
  main.on('page-title-updated',event=>event.preventDefault())
  main.webContents.setWindowOpenHandler(()=>({action:'deny'}))
  main.webContents.on('will-navigate',(e,url)=>{if(!url.startsWith('app://story/'))e.preventDefault()})
  session.defaultSession.setPermissionRequestHandler((contents,permission,callback,details)=>callback(storyPermissionAllowed(contents,main.webContents,permission,details)))
  session.defaultSession.setPermissionCheckHandler((contents,permission,requestingOrigin,details)=>storyPermissionAllowed(contents,main.webContents,permission,details,requestingOrigin))
  const connectOrigins=(offline||(!app.isPackaged && process.env.STORY_DEV_SERVER))?'http://127.0.0.1:*':"https:"
  session.defaultSession.webRequest.onHeadersReceived({urls:['app://story/*']},(details,callback)=>callback({responseHeaders:{...details.responseHeaders,'Content-Security-Policy':[`default-src 'self'; script-src 'self' character: 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' character: data: ${connectOrigins}; connect-src 'self' character: ${connectOrigins}; media-src 'self' blob:; object-src 'none'; frame-src 'none'; base-uri 'none'`]}}))
  await main.loadURL('app://story/index.html')
}).catch(error=>{dialog.showErrorBox('幕间启动失败',error.message);runner?.stop();app.quit()})
app.on('before-quit',()=>runner?.stop())
app.on('window-all-closed',()=>app.quit())
