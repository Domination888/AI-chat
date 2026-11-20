import { acceptSession, apiBase, connect, isSessionExpired, json } from "./api.mjs"
import { watch } from "vue"

export function useReleaseSession({
  accountNotice, accountOpen, authNotice, busy, chapters, codeWait, connected, conversation,
  credentials, draftRecovery, error, images, initializeCompanion, loadConversationState,
  loadLibrary, loadMessages, me, notice, noticeIsError, page, pendingContinuation,
  pendingDailyIssue, register, roles, selected, sendDraftRecovery, server, text,
}) {
  let codeTimer = null
  let noticeTimer=null,noticeDuration=6000
  const noticeHolds=new Set()
  function clearNotice(){clearTimeout(noticeTimer);noticeTimer=null;notice.value='';noticeIsError.value=false;noticeHolds.clear()}
  function pauseNotice(reason){noticeHolds.add(reason);clearTimeout(noticeTimer);noticeTimer=null}
  function resumeNotice(reason){noticeHolds.delete(reason);clearTimeout(noticeTimer);if(notice.value&&!noticeHolds.size)noticeTimer=setTimeout(clearNotice,noticeDuration)}
  function toast(msg,{isError=false}={}){
    clearNotice()
    const message=String(msg||'')
    if(accountOpen.value){accountNotice.value=message;return}
    notice.value=message;noticeIsError.value=isError
    noticeDuration=Math.max(isError?15000:6000,Math.min(30000,message.length*90))
    resumeNotice()
  }
  // Clear only the old context's notice; synchronous watching preserves a new
  // success message posted after navigation (for example creating a branch).
  watch([page,conversation],clearNotice,{flush:'sync'})
  watch(accountOpen,open=>{if(open)clearNotice();else accountNotice.value=''},{flush:'sync'})
  async function guarded(fn){clearNotice();error.value='';if(accountOpen.value)accountNotice.value='';busy.value=true;try{return await fn()}catch(e){if(!isSessionExpired(e)){const message=e.message.replace(/^Error invoking remote method '[^']+':\s*(?:Error:\s*)?/,'');error.value=message;toast(message,{isError:true})}}finally{busy.value=false}}
  async function connectServer(){sendDraftRecovery.clear();await guarded(async()=>{await connect(server.value);const h=await json('/api/health');if(h.mode!=='cloud')throw new Error('请连接商业版服务');connected.value=true})}
  async function sendCode(){await guarded(async()=>{if(!credentials.value.email.trim())throw new Error('请先填写邮箱');if(!credentials.value.invitation.trim())throw new Error('请先填写邀请码');await json('/api/v1/auth/email-code',credentials.value,'POST');toast('验证码已发送，请检查邮箱');codeWait.value=60;clearInterval(codeTimer);codeTimer=setInterval(()=>{if(--codeWait.value<=0)clearInterval(codeTimer)},1000)})}
  async function authenticate(){await guarded(async()=>{const s=await json(register.value?'/api/v1/auth/register':'/api/v1/auth/login',credentials.value,'POST');me.value=await acceptSession(s);credentials.value.password='';await loadLibrary();await initializeCompanion();const saved=draftRecovery.take(me.value.id,apiBase());if(saved){text.value=saved.text;images.value=saved.images;const chapter=chapters.value.find(c=>c.id===saved.conversationId);if(chapter){selected.value=roles.value.find(r=>r.id===chapter.role_id);conversation.value=chapter.id;page.value='story';pendingContinuation.value=saved.continuationHistoryId?{conversation:chapter.id,id:saved.continuationHistoryId}:null;pendingDailyIssue.value=saved.dailyIssueDate?{conversation:chapter.id,date:saved.dailyIssueDate}:null;await Promise.all([loadMessages(),loadConversationState()])}if(saved.text||saved.images.length)toast('未发送的文字和照片已恢复，请确认后再发送。')}authNotice.value=''})}
  function clearEmailTimer() { clearInterval(codeTimer) }

  return { clearNotice, pauseNotice, resumeNotice, toast, guarded, connectServer, sendCode, authenticate, clearEmailTimer }
}
