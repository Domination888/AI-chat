import { initialize, json, runtimeInfo } from "./api.mjs"

export function useReleaseAccount({
  accountNotice, accountOpen, accountTab, askAction, autoRead, cancelRecording, companionOpen,
  conversation, conversationState, dailyStatus, draftRecovery, error, guarded, images,
  initializeCompanion, installingModels, loadLibrary, messages, mobileNav, model, nativeInfo,
  offline, page, pendingContinuation, resetCharacterImport, sendDraftRecovery, showScene, signOut,
  speech, stop, text, toast, toolsOpen, webSearch,
}) {
  async function openAccount(tab='model'){mobileNav.value=false;accountTab.value=tab==='data'?'data':'model';accountOpen.value=true;error.value='';accountNotice.value='';await guarded(async()=>{model.value={...await json('/api/v1/me/model-connection'),apiKey:''}})}
  async function saveModel(){await guarded(async()=>{await json('/api/v1/me/model-connection',model.value,'PUT');model.value={...await json('/api/v1/me/model-connection'),apiKey:''};toast('模型连接已保存')})}
  async function testModel(){await guarded(async()=>{try{await json('/api/v1/me/model-connection/test',{},'POST');toast('模型连接正常')}catch(e){throw new Error(e.message+'。'+(model.value.custom?'请核对 API 地址、模型名称与密钥，保存后再测试。':offline?'请导入完整离线模型包，准备完成后再测试。':'平台模型暂未就绪，请联系服务方，或填写自己的模型连接。'))}})}
  async function resetModel(){await guarded(async()=>{await json('/api/v1/me/model-connection',undefined,'DELETE');await openAccount();toast('已恢复默认模型')})}
  async function exportData(){await guarded(async()=>{const data=await json('/api/v1/me/data');const source=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const link=document.createElement('a');link.href=source;link.download='幕间-故事备份.json';link.click();accountNotice.value='故事备份已生成并发起下载，请在下载目录中查看。';setTimeout(()=>URL.revokeObjectURL(source),1000)})}
  async function refreshDataState(){resetCharacterImport();pendingContinuation.value=null;sendDraftRecovery.clear();draftRecovery.clear();speech.stop();autoRead.value=false;cancelRecording();showScene.value=false;conversation.value='';messages.value=[];conversationState.value=null;images.value=[];text.value='';page.value='library';toolsOpen.value=false;companionOpen.value=false;accountNotice.value='';model.value={};dailyStatus.value={};webSearch.value=false;await loadLibrary();await initializeCompanion()}
  async function importData(e){const file=e.target.files[0];if(!file)return;await guarded(async()=>{await json('/api/v1/offline/import',JSON.parse(await file.text()),'POST');await refreshDataState();toast('数据已导入，记忆索引正在后台重建')});e.target.value=''}
  async function installModels(){
    if(installingModels.value)return
    installingModels.value=true
    try{await guarded(async()=>{
      await stop()
      accountNotice.value=''
      try{
        const result=await window.storyDesktop.installModelPack()
        if(result?.installed){
          await initialize()
          nativeInfo.value=runtimeInfo
          model.value={...await json('/api/v1/me/model-connection'),apiKey:''}
          accountTab.value='model'
          toast('模型包已导入，本地服务已重启')
        }else accountNotice.value='已取消模型包导入'
      }catch(e){
        accountNotice.value=''
        // A failed activation can restart the previous pack on a different port.
        // Rebind the client before allowing another operation, while retaining the real error.
        try{await initialize();nativeInfo.value=runtimeInfo}
        catch(recovery){throw new Error(`${e.message}；本地连接恢复失败：${recovery.message}`)}
        throw e
      }
    })}finally{installingModels.value=false}
  }
  async function deleteData(){if(!await askAction({title:offline?'清空本地数据':'删除账号与故事',description:offline?'永久清空这台设备上的故事、照片、自建角色、模型连接与密钥、MCP 服务、技能和陪伴偏好。备份不包含模型连接、MCP 服务及其密钥，恢复后需要重新设置。请先导出备份，此操作无法撤销。':'永久删除账号、私人故事和模型密钥。请先导出备份，此操作无法撤销。',confirmLabel:offline?'确认清空':'确认删除'}))return;await stop();await guarded(async()=>{await json('/api/v1/me/data',undefined,'DELETE');accountOpen.value=false;if(offline){await refreshDataState();page.value='library';conversation.value='';messages.value=[]}else await signOut()})}

  return { openAccount, saveModel, testModel, resetModel, exportData, importData, installModels, deleteData }
}
