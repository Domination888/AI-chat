// Only structured execution events produce cards. Assistant prose is never evidence.
export function applyToolStatus(message,payload){
  if(!message||!payload)return
  if(payload.kind==='plan'){
    message.toolPlanning={phase:payload.phase,available:payload.available,message:payload.message}
    return
  }
  if(!payload.id||!payload.name||!['running','succeeded','failed'].includes(payload.phase))return
  if(!message.toolCalls)message.toolCalls=[]
  const calls=message.toolCalls
  const index=calls.findIndex(call=>call.id===payload.id)
  const value={...payload}
  if(index<0)calls.push(value)
  else calls.splice(index,1,value)
}
export function toolPhaseLabel(phase){return ({running:'正在调用',succeeded:'执行成功',failed:'执行失败'})[phase]||'调用记录'}
