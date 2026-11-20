import { applyToolStatus } from "./toolTrace.mjs"
import { isSessionExpired, json, request } from "./api.mjs"
import { live2dController } from "../live2d/live2d-controller.js"
import { localChatBlocked } from "./localServices.mjs"
import { nextTick, watch } from "vue"
import { readSse } from "../utils/sse.js"
import { useVoiceRecorder } from "../composables/useVoiceRecorder.js"

export function useReleaseStream({
  bottom, conversation, generating, guarded, images, installingModels, loadChapters, loadMessages,
  me, messages, model, nativeInfo, offline, offlineModelMissing, openAccount, page,
  pendingContinuation, pendingDailyIssue, proactive, replyState, selected, sendDraftRecovery,
  speech, stopping, text, toast, webSearch,
}) {
  function stopSpeech(){speech.stop()}
  async function stop(cancelCapture=true){
    const seq=++replyState.generation,c=conversation.value,active=generating.value
    const current=()=>!replyState.disposed&&seq===replyState.generation&&c===conversation.value
    replyState.proactiveIndex=null;replyState.controller?.abort();replyState.controller=null;stopSpeech();if(cancelCapture)cancelRecording()
    if(!active||!c){generating.value=false;stopping.value=false;return}
    stopping.value=true
    try{await json('/api/chat/interrupt',{conversationId:c},'POST')}
    catch(e){if(current())toast(e.message,{isError:true})}
    finally{
      // The aborted send's finally is intentionally stale. This stop owns the
      // reload after the server has persisted its interrupted reply and real IDs.
      if(current()){
        try{await loadMessages();await loadChapters()}
        catch(e){if(current())toast(`回复已停止，记录刷新失败：${e.message}。请重新打开当前篇章。`,{isError:true})}
        finally{if(current()){generating.value=false;stopping.value=false}}
      }
    }
  }
  async function send(audioPayload){
    if(installingModels.value)return
    if(offline&&!images.value.length&&nativeInfo.value.localServices?.llm&&localChatBlocked(nativeInfo.value,model.value.custom)){toast('聊天模型暂未就绪，草稿已保留');return}
    if(offlineModelMissing.value&&!images.value.length){await promptModelSetup();return}
    speech.unlock().catch(()=>{});await proactive.activity()
    if(!conversation.value||(!text.value.trim()&&!images.value.length&&!audioPayload))return
    await stop()
    if(!conversation.value||!selected.value||(!offline&&!me.value))return
    const seq=++replyState.generation,c=conversation.value,body={conversationId:c,roleId:selected.value.id,continuationHistoryId:pendingContinuation.value?.conversation===c?pendingContinuation.value.id:null,message:text.value,dailyIssueDate:pendingDailyIssue.value?.conversation===c?pendingDailyIssue.value.date:null,search:webSearch.value,images:[...images.value],...(audioPayload || {})}
    const pending={...body,continuationSelection:pendingContinuation.value,dailySelection:pendingDailyIssue.value,accepted:false,draft:sendDraftRecovery.capture(body.message,body.images,body.dailyIssueDate,body.continuationHistoryId)}
    replyState.pendingSend=pending;text.value='';images.value=[]
    messages.value.push({role:'user',content:body.message||'正在识别语音…',images:body.images,clientSendId:seq})
    const mine=messages.value.length-1,reply={role:'ai',content:'',clientSendId:seq}
    messages.value.push(reply);generating.value=true;replyState.controller=new AbortController();await bottom()
    let completed=false
    try{
      const response=await request('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),signal:replyState.controller.signal})
      if(!response.ok){const detail=await response.json().catch(()=>({}));throw new Error(detail.message||(response.status===429?'发送过于频繁，请稍后重试':'模型请求失败'))}
      pending.accepted=true
      for await(const {event,payload} of readSse(response.body,replyState.controller.signal)){if(seq!==replyState.generation||c!==conversation.value)return;if(event==='text'){messages.value[mine+1].content+=payload.text;speech.addText(payload.text);await bottom()}if(event==='emotion')live2dController.triggerEmotion(payload.emotion);if(event==='tool_status')applyToolStatus(messages.value[mine+1],payload);if(event==='sources')messages.value[mine+1].sources=payload.sources;if(event==='search_status')messages.value[mine+1].searchStatus=payload.message;if(event==='asr')messages.value[mine].content=payload.text;if(event==='warning')toast(payload.message,{isError:true});if(event==='error'){if(payload.accepted===false)pending.accepted=false;throw new Error(payload.message);}if(event==='done'){completed=true;speech.addText('',true)}}
      if(!completed)throw new Error('连接提前结束，请重新连接后继续')
    }catch(e){
      const expired=isSessionExpired(e),current=seq===replyState.generation&&c===conversation.value
      const recovery=!pending.accepted&&!expired?sendDraftRecovery.recover(pending.draft,{inline:current}):null
      if(!pending.accepted&&current)messages.value=messages.value.filter(message=>message.clientSendId!==seq)
      if(e.name!=='AbortError'&&!expired&&current&&recovery!=='stale'){
        const explanation=recovery==='restored'?' 内容已放回输入框，请确认后再发送。':recovery==='saved'?' 未发送的内容已暂存，可在输入框上方恢复；正在编辑的文字已保留。':''
        toast(e.message+explanation,{isError:true})
      }
    }finally{
      if(pending.accepted&&pendingContinuation.value===pending.continuationSelection)pendingContinuation.value=null
      if(pending.accepted&&pendingDailyIssue.value===pending.dailySelection)pendingDailyIssue.value=null
      if(replyState.pendingSend===pending)replyState.pendingSend=null
      if(seq===replyState.generation){generating.value=false;replyState.controller=null;await loadMessages().catch(()=>{});await loadChapters().catch(()=>{})}
    }
  }
  const {isRecording,startRecording,stopRecording,cancelRecording}=useVoiceRecorder({beforeStart:()=>stop(false),onAudio:async blob=>{const bytes=new Uint8Array(await blob.arrayBuffer());let encoded='';for(let i=0;i<bytes.length;i+=8192)encoded+=String.fromCharCode(...bytes.subarray(i,i+8192));await send({inputMode:'audio',audioBase64:btoa(encoded),audioFormat:blob.type.includes('mp4')?'mp4':'webm'})},onError:e=>toast(e.message,{isError:true})})
  watch(page,(value,previous)=>{if(previous==='story'&&value!=='story')cancelRecording()},{flush:'sync'})
  function restoreFailedDraft(id){
    const result=sendDraftRecovery.restore(id)
    if(!result)return
    toast(result==='swapped'?'未发送的内容已放回输入框，刚才编辑的草稿也已暂存。':'草稿已放回输入框，请确认后发送。')
    nextTick(()=>document.querySelector('[aria-label="输入消息"]')?.focus())
  }
  async function promptModelSetup(){await openAccount();toast('请先导入离线模型包，或连接你自己的模型。正在编辑的文字会保留；语音输入也需要准备语音服务。')}
  async function toggleRecording(){if(isRecording.value){stopRecording();return}if(installingModels.value)return;if(offlineModelMissing.value){await promptModelSetup();return}await startRecording()}
  async function readLast(){const m=[...messages.value].reverse().find(m=>m.role==='ai');if(m)await guarded(()=>speech.read(m.content))}
  async function addImages(e){for(const f of [...e.target.files]){if(images.value.length>=5){toast('最多添加 5 张图片');break}if(f.size>5*1024*1024){toast('单张图片不超过 5MB');continue}const data=await new Promise(resolve=>{const r=new FileReader();r.onload=()=>resolve(r.result);r.readAsDataURL(f)});images.value.push(data)}e.target.value=''}

  return { stopSpeech, stop, send, isRecording, cancelRecording, restoreFailedDraft, toggleRecording, readLast, addImages }
}
