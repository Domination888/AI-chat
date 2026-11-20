import { onMounted } from 'vue'
import { apiBase, initialize, json, runtimeInfo } from './api.mjs'

export function useReleaseStartup({
  authNotice, booting, connected, desktop, error, initializeCompanion, loadLibrary, me,
  nativeInfo, nativeTimer, offline, replyState, server, toast,
}) {
  onMounted(async()=>{try{
    const restoredUser=await initialize()
    nativeInfo.value=runtimeInfo
    if(offline&&desktop)nativeTimer.value=setInterval(async()=>{try{const info=await window.storyDesktop.bootstrap();if(!replyState.disposed)nativeInfo.value=info}catch{if(!replyState.disposed)error.value='本地服务状态暂时无法刷新，草稿已保留'}},2000)
    server.value=apiBase()
    if(!offline&&runtimeInfo.sessionExpired)authNotice.value='登录状态已失效，请重新登录。'
    if(apiBase()||offline){
      const health=await json('/api/health');connected.value=true
      // A new commercial profile and a reloaded browser have no access token.
      // Only initialize's successfully restored session may load private data.
      if(offline||(health.mode==='cloud'&&restoredUser)){
        try{me.value=offline?(await json('/api/v1/me')).user:restoredUser;await loadLibrary();await initializeCompanion()}
        catch(e){if(offline)throw e}
      }
    }
  }catch(e){error.value=e.message;toast(e.message,{isError:true})}finally{booting.value=false}})
}
