import { apiBase, json } from "./api.mjs"
import { manualContinuationAvailable, regenerationAction } from "./messageActions.mjs"
import { nextTick } from "vue"
import { prepareReplayImages } from "./replayImages.mjs"

export function useReleaseHistory({
  askAction, chapters, conversation, guarded, historyBusy, images, loadChapters,
  loadConversationState, loadMessages, messages, openChapter, page, pendingContinuation,
  pendingDailyIssue, send, sendDraftRecovery, stop, text, toast, triggerProactive,
}) {
  const rewindScope='这条消息之前的故事会保留。同一世界线中，这条消息及之后的对话（含延续篇章）将被回退；独立分支不受影响。如需保留后续剧情，请先创建分支。'
  async function applyRewind(target,messageId){
    if(historyBusy.value||conversation.value!==target)return false
    historyBusy.value=true
    try{
      await stop()
      if(conversation.value!==target)return false
      return await guarded(async()=>{await json(`/api/rp/${target}/rewind/${messageId}`,{},'POST');if(conversation.value!==target)return false;await loadMessages();await loadChapters();if(conversation.value!==target)return false;await loadConversationState();return conversation.value===target})
    }finally{historyBusy.value=false}
  }
  async function rewind(m){
    const target=conversation.value
    if(historyBusy.value||!await askAction({title:'回到这个片刻',description:rewindScope,preview:m.content,confirmLabel:'确认回退'}))return
    await applyRewind(target,m.id)
  }
  async function branch(m){await stop();await guarded(async()=>{const r=await json(`/api/rp/${conversation.value}/branch/${m.id}`,{},'POST');await loadChapters();await openChapter(chapters.value.find(c=>c.id===r.conversationId));toast('已开启独立分支，原故事已保留')})}
  async function replayPictures(m){
    if(historyBusy.value)return null
    historyBusy.value=true
    try{return await guarded(()=>prepareReplayImages(m.images||[],{base:apiBase()}))}
    finally{historyBusy.value=false}
  }
  function dailyIssueForReplay(m){
    const index=messages.value.indexOf(m),reply=index>=0?messages.value[index+1]:null
    const date=m.aiDaily?.date||(reply?.role==='ai'?reply.aiDaily?.date:null)
    return /^\d{4}-\d{2}-\d{2}$/.test(date||'')?date:null
  }
  async function editMessage(m){
    if(historyBusy.value)return
    const target=conversation.value,issueDate=dailyIssueForReplay(m),continuationId=messages.value[messages.value.indexOf(m)+1]?.continuationHistoryId
    const value=await askAction({title:'编辑这句话',description:rewindScope+' 修改后会放回输入框，由你确认发送。',input:true,value:m.content,confirmLabel:'回退并应用修改'})
    if(value===null||conversation.value!==target)return
    const pictures=await replayPictures(m)
    if(!pictures||conversation.value!==target||!await applyRewind(target,m.id)||conversation.value!==target)return
    pendingContinuation.value=continuationId?{conversation:target,id:continuationId}:null;text.value=value;images.value=pictures;pendingDailyIssue.value=issueDate?{conversation:target,date:issueDate}:null;toast('修改已放回输入框，确认后发送')
    await nextTick();document.querySelector('[aria-label="输入消息"]')?.focus()
  }
  async function rewrite(index,continuePaused=false){
    if(historyBusy.value)return
    const before=messages.value[index-1],reply=messages.value[index],target=conversation.value
    const action=regenerationAction(messages.value,index)
    if(!action){toast('这条开场白没有对应的提问。写下一句话，或使用“请 TA 开场”继续故事。');return}
    if(action.kind==='continue'||(continuePaused&&manualContinuationAvailable(reply))){
      const prompt=index===messages.value.length-1?'请从上一条回复中断的地方继续，不重复已经写过的内容。':`请续写下面这段未完成的回复，从末尾继续，不重复已经写过的内容：\n${reply.content.slice(-1500)}`
      if(text.value!==prompt||images.value.length){
        if(text.value||images.value.length)sendDraftRecovery.recover(sendDraftRecovery.capture(text.value,images.value,pendingDailyIssue.value?.date,pendingContinuation.value?.id),{inline:false})
        pendingDailyIssue.value=null;pendingContinuation.value={conversation:target,id:reply.id};text.value=prompt;images.value=[]
      }
      toast('继续请求已放入输入框，确认后发送；刚才的草稿会保留')
      await nextTick();document.querySelector('[aria-label="输入消息"]')?.focus()
      return
    }
    if(action.kind==='proactive'){
      if(!await askAction({title:'邀请 TA 再次开场',description:'保留这封主动来信和现有故事，邀请 TA 说一句新的开场白。',preview:reply.content,confirmLabel:'再次开场'}))return
      if(conversation.value===target)await triggerProactive()
      return
    }
    const issueDate=dailyIssueForReplay(before||{}),continuationId=reply?.continuationHistoryId
    const retry=['failed','interrupted'].includes(reply?.status)
    if(!await askAction({title:retry?'重试这次回复':'重新生成回复',description:(retry?'将重新提交下面这句话，重新生成角色回复。':'将重新生成角色对这句话的回复。')+rewindScope,preview:before.content,confirmLabel:retry?'确认重试':'确认重写'}))return
    const pictures=await replayPictures(before)
    if(!pictures||conversation.value!==target||!await applyRewind(target,before.id)||conversation.value!==target)return
    pendingContinuation.value=continuationId?{conversation:target,id:continuationId}:null;text.value=before.content;images.value=pictures;pendingDailyIssue.value=issueDate?{conversation:target,date:issueDate}:null;await send()
  }
  async function bookmark(m){await guarded(async()=>{await json(`/api/rp/${conversation.value}/bookmark/${m.turnId}`,{value:!m.bookmarked},'PUT');m.bookmarked=!m.bookmarked})}
  async function deleteChapter(){if(!await askAction({title:'删除当前篇章',description:'当前篇章及其中的对话将永久删除。请先导出需要保留的故事。',confirmLabel:'删除篇章'}))return;await stop();await guarded(async()=>{await json(`/api/conversation/${conversation.value}`,undefined,'DELETE');conversation.value='';messages.value=[];page.value='library';await loadChapters()})}

  return { rewind, branch, editMessage, rewrite, bookmark, deleteChapter }
}
