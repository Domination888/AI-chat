const names={llm:'聊天模型',embedding:'记忆索引服务',voice:'语音服务',backend:'本地故事服务'}
export function localServiceNotices(info){return Object.entries(info.localServices||{}).filter(([,s])=>!['ready','stopped'].includes(s.state)).map(([kind,s])=>({kind,retryAvailable:!!s.retryAvailable,text:`${names[kind]||'本地服务'}${s.state==='recovering'?'正在恢复':s.state==='starting'?'正在准备':s.state==='unhealthy'?'暂时无法连接':'已停止'}${s.attempts?`（已尝试 ${s.attempts} 次）`:''}。${kind==='embedding'?'长期记忆索引暂不可用，文字和照片草稿会保留。':kind==='backend'?'故事服务暂不可用，请重新打开应用；草稿会保留。':'草稿会保留。'}`}))}
export function localChatBlocked(info,custom){
  if(custom)return false
  if(info.localServices?.llm)return info.localServices.llm.state!=='ready'
  return !info.externalModels&&['missing','error','starting'].includes(info.modelState)
}
