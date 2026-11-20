<template src="./ReleaseAppView.html"></template>

<script setup>
import {localServiceNotices,localChatBlocked} from './localServices.mjs'
const localRetryBusy=ref(false)
async function retryLocalService(kind){if(localRetryBusy.value)return;localRetryBusy.value=true;try{await window.storyDesktop.retryLocalService(kind);nativeInfo.value=await window.storyDesktop.bootstrap()}catch(e){toast('本地服务重试未完成，请稍后再试',{isError:true})}finally{localRetryBusy.value=false}}

import { ref, computed, watch, onBeforeUnmount, nextTick } from "vue"
import { runtimeInfo, apiBase, logout, json, distribution, imageUrls, onSessionExpired } from "./api.mjs"
import ChatMessageList from '../components/chat/ChatMessageList.vue'
import RpPanel from '../components/RpPanel.vue'
import CompanionPanel from './CompanionPanel.vue'
import StoryMemory from './StoryMemory.vue'
import CapabilityPanel from './CapabilityPanel.vue'
import FeatureGuide from './FeatureGuide.vue'
import ActionDialog from './ActionDialog.vue'
import { createActionRequest } from './actionRequest.mjs'

import { createSessionDraftRecovery } from './sessionDraft.mjs'
import { useSendDraftRecovery } from './sendDraftRecovery.mjs'

import {applyToolStatus} from './toolTrace.mjs'
import CharacterStage from './CharacterStage.vue'
import {useSpeechQueue} from './useSpeechQueue.js'
import {useReleaseProactive} from './useReleaseProactive.js'
import {live2dController} from '../live2d/live2d-controller.js'
import { locateWorldContext } from '../utils/worldLocation.js'

import { useReleaseDialogs } from './useReleaseDialogs.mjs'
import { useReleaseGuide } from './useReleaseGuide.mjs'
import { useReleaseSession } from './useReleaseSession.mjs'
import { useReleaseStream } from './useReleaseStream.mjs'
import { useReleaseHistory } from './useReleaseHistory.mjs'
import { useReleaseAccount } from './useReleaseAccount.mjs'
import { useReleaseStartup } from './useReleaseStartup.mjs'
import './release.css'

const nativeInfo=ref({}),nativeTimer=ref(null)
const authNotice=ref(''),draftRecovery=createSessionDraftRecovery()
const replyState={pendingSend:null, proactiveIndex:null, controller:null, generation:0, disposed:false}
const companionOpen=ref(false),companionTab=ref('world'),dailyStatus=ref({})
let dailyTimer=null,locationController=null,characterImported=false
function openCompanion(tab='world'){companionTab.value=tab;companionOpen.value=true;mobileNav.value=false}
let dailyRequest=0
function acceptDailyStatus(value){dailyRequest++;dailyStatus.value=value}
async function loadDailyStatus(){if(!me.value)return;const revision=++dailyRequest,owner=me.value;try{const value=await json('/api/v1/me/ai-daily');if(revision===dailyRequest&&owner===me.value)dailyStatus.value=value}catch{}}
async function initializeCompanion(){locationController?.abort();locationController=new AbortController();await loadDailyStatus();const s=await json('/api/v1/me/companion');webSearch.value=!!s.searchEnabled;if(s.geolocationEnabled)locateWorldContext({timezone:s.timezone,maximumAge:300000,allowIpFallback:offline&&s.worldNetworkEnabled,signal:locationController.signal}).catch(()=>{});clearInterval(dailyTimer);dailyTimer=setInterval(loadDailyStatus,60000)}
const pendingContinuation=ref(null)
const pendingDailyIssue=ref(null)

function discussDaily(selection){if(!conversation.value)return;pendingContinuation.value=null;page.value='story';text.value=selection.prompt;pendingDailyIssue.value={conversation:conversation.value,date:selection.issueDate}}
const offline=distribution==='offline', desktop=!!window.storyDesktop
const me=ref(null),booting=ref(true),connected=ref(false),server=ref(apiBase()),register=ref(false),busy=ref(false),error=ref(''),notice=ref(''),noticeIsError=ref(false),codeWait=ref(0)
const credentials=ref({email:'',password:'',username:'',invitation:'',code:''})
watch(register,()=>{authNotice.value='';error.value=''},{flush:'sync'})
const page=ref('library'),mobileNav=ref(false),roles=ref([]),chapters=ref([]),selected=ref(null),conversation=ref(''),messages=ref([]),hasOlder=ref(false),showScene=ref(false),showSearch=ref(false),search=ref(''),appliedSearch=ref(''),generating=ref(false),stopping=ref(false),text=ref(''),images=ref([]),messageList=ref(null),fileInput=ref(null)
watch(conversation,()=>{pendingContinuation.value=null;pendingDailyIssue.value=null},{flush:'sync'})
watch(text,value=>{if(!value.trim()&&!generating.value){pendingDailyIssue.value=null;pendingContinuation.value=null}})
const accountTab=ref('model'),accountNotice=ref(''),chapterMenu=ref(null),chapterMenuOpen=ref(false)
const sceneTab=ref('scene'),conversationState=ref(null),modeBusy=ref(false)
let stateRequest=0
const accountOpen=ref(false),model=ref({}),importInput=ref(null),showRoleForm=ref(false),roleForm=ref({})
const installingModels=ref(false)
const offlineModelMissing=computed(()=>offline&&!model.value.custom&&!nativeInfo.value.externalModels&&!nativeInfo.value.localServices?.llm&&['missing','error'].includes(nativeInfo.value.modelState))
const sendDisabled=computed(()=>(!text.value.trim()&&!images.value.length)||(offlineModelMissing.value&&!images.value.length)||(offline&&!images.value.length&&localChatBlocked(nativeInfo.value,model.value.custom)))
const actionDialog=ref(null),historyBusy=ref(false)
const {ask:askAction,settle:settleAction}=createActionRequest(value=>{actionDialog.value=value})
const toolsOpen=ref(false),toolsTab=ref('mcp'),showCharacter=ref(true),webSearch=ref(false)
const sendDraftRecovery=useSendDraftRecovery({
  scope:()=>({userId:me.value?.id,base:offline?'offline':apiBase(),conversationId:conversation.value,inStory:page.value==='story'}),
  readDraft:()=>({text:text.value,images:images.value,continuationHistoryId:pendingContinuation.value?.id,dailyIssueDate:pendingDailyIssue.value?.date}),
  writeDraft:draft=>{pendingContinuation.value=draft.continuationHistoryId?{conversation:conversation.value,id:draft.continuationHistoryId}:null;text.value=draft.text;images.value=[...draft.images];pendingDailyIssue.value=draft.dailyIssueDate?{conversation:conversation.value,date:draft.dailyIssueDate}:null},
})
const failedDrafts=sendDraftRecovery.visible
useReleaseDialogs({
  accountOpen, actionDialog, chapterMenu, chapterMenuOpen, companionOpen, installingModels,
  mobileNav, showRoleForm, showScene, showSearch, toggleSearch, toolsOpen,
})
const speech=useSpeechQueue({roleId:()=>selected.value?.id,onError:e=>toast(e.message,{isError:true})})
const speaking=speech.speaking,autoRead=speech.enabled
const proactive=useReleaseProactive({onEvent:handleProactive,onError:()=>{}})
watch(conversation,value=>proactive.attach(value))
// Invalidate the previous chapter before navigation or draft recovery starts a new request.
watch(conversation,()=>{conversationState.value=null;stateRequest++},{flush:'sync'})
async function loadConversationState(){
  const target=conversation.value,requestId=++stateRequest
  if(!target)return
  const result=await json(`/api/rp/${target}`)
  if(target===conversation.value&&requestId===stateRequest)conversationState.value=result.state
}
const { refreshStory, openScene, switchConversationMode, openGuide, navigateGuide } = useReleaseGuide({
  choose, conversation, fileInput, guarded: (...args) => guarded(...args), loadChapters,
  loadConversationState, loadMessages, mobileNav, modeBusy, nativeInfo, newStory,
  openAccount: (...args) => openAccount(...args), openCompanion, openTools, page, roleForm, roles,
  sceneTab, selected, showCharacter, showRoleForm, showScene, showSearch,
  stop: (...args) => stop(...args), toast: (...args) => toast(...args),
})
async function clearSearch(){search.value='';await guarded(loadMessages)}
async function toggleSearch(){showSearch.value=!showSearch.value;if(!showSearch.value&&(search.value||appliedSearch.value))await clearSearch()}
function openTools(tab='mcp'){toolsTab.value=tab;toolsOpen.value=true;mobileNav.value=false}
async function triggerProactive(){
  await guarded(async()=>{
    const result=await proactive.trigger()
    if(!result){toast('请先打开一个篇章，再邀请 TA 开场');return}
    if(!result.triggered)toast(`暂未主动开场：${({paused_or_story_mode:'请在陪伴偏好开启主动搭话，并在故事与记忆切换为现实陪伴',no_listener:'连接正在建立，请稍后重试',busy:'角色正在回复，请等回复结束'})[result.status]||result.status}`)
    else toast('已邀请 TA 再次开场，原有故事会保留')
  })
}
async function feedback(msg,value){await guarded(async()=>{await json(`/api/proactive-research/candidates/${msg.candidateId}/feedback`,{feedback:value},'POST');msg.feedback=value})}
async function setWebSearch(){await guarded(async()=>{await json('/api/v1/me/companion',{searchEnabled:webSearch.value},'PUT');await loadDailyStatus()})}
async function openSource(url){if(!url?.startsWith('https://'))return;if(window.storyDesktop?.openSource)await window.storyDesktop.openSource(url);else window.open(url,'_blank','noopener,noreferrer')}
function handleProactive(event,payload){if(event==='proactive'){speech.stop();generating.value=true;messages.value.push({role:'ai',content:'',isProactive:true,...payload});replyState.proactiveIndex=messages.value.length-1;bottom()}else if(event==='text'&&replyState.proactiveIndex!==null){messages.value[replyState.proactiveIndex].content+=payload.text||payload.delta||'';speech.addText(payload.text||payload.delta||'');bottom()}else if(event==='emotion')live2dController.triggerEmotion(payload.emotion);else if(event==='tool_status'&&replyState.proactiveIndex!==null)applyToolStatus(messages.value[replyState.proactiveIndex],payload);else if(event==='done'&&replyState.proactiveIndex!==null){speech.addText('',true);replyState.proactiveIndex=null;generating.value=false;loadMessages();loadChapters()}else if(event==='error'&&replyState.proactiveIndex!==null){toast(payload.message,{isError:true});replyState.proactiveIndex=null;generating.value=false;speech.stop();loadMessages()}}
const currentChapter=computed(()=>chapters.value.find(c=>c.id===conversation.value))
let selection=0
const roleName=id=>roles.value.find(r=>r.id===id)?.name || '角色'
const { clearNotice, pauseNotice, resumeNotice, toast, guarded, connectServer, sendCode, authenticate, clearEmailTimer } = useReleaseSession({
  accountNotice, accountOpen, authNotice, busy, chapters, codeWait, connected, conversation,
  credentials, draftRecovery, error, images, initializeCompanion, loadConversationState,
  loadLibrary, loadMessages, me, notice, noticeIsError, page, pendingContinuation,
  pendingDailyIssue, register, roles, selected, sendDraftRecovery, server, text,
})
async function loadChapters(){chapters.value=await json('/api/v1/conversations')}
async function loadLibrary(){if(desktop&&runtimeInfo.characterAvailable&&!characterImported){await json('/api/v1/characters/shu/import',await window.storyDesktop.characterPack(),'POST');characterImported=true;}roles.value=(await json('/api/roles')).map(r=>({...r,storyPortrait:r.avatar})).sort((a,b)=>Number(b.roleCode==='shu')-Number(a.roleCode==='shu'));await loadChapters();model.value={...await json('/api/v1/me/model-connection'),apiKey:''}}
async function choose(r){if(!r)return;speech.unlock().catch(()=>{});selected.value=r;await guarded(()=>newStory());mobileNav.value=false}
async function newStory(world){if(!selected.value)return;await stop();conversation.value='conv_'+crypto.randomUUID();const state={relationship:'初次相遇；关系由故事决定',mode:selected.value.roleCode==='shu'?'companion':'story',style:selected.value.roleCode==='shu'?'chat':'story',location:selected.value.ownerId?'':({garden:'书店的窗边',observatory:'山顶的观测站',voyager:'通向远方的岔路口'}[selected.value.roleCode] || '')};await json('/api/rp/sessions',{conversationId:conversation.value,roleId:selected.value.id,continueWorld:typeof world==='string'?world:null,state},'POST');await json(`/api/rp/${conversation.value}/greeting`,{},'POST');page.value='story';search.value='';await loadChapters();await Promise.all([loadMessages(),loadConversationState()]);showScene.value=false}
async function continueWorld(world){await guarded(()=>newStory(world));showScene.value=false}
async function continueCurrentStory(){
  if(busy.value||!conversation.value)return
  const target=conversation.value
  await guarded(async()=>{
    const state=await json(`/api/rp/${target}`)
    if(conversation.value!==target)return
    if(!state.worldId)throw new Error('当前故事尚未加载完成，请稍后再试')
    await newStory(state.worldId)
    toast('已延续当前故事，场景、约定和共同经历会保留')
  })
}
async function openChapter(c){await stop();selected.value=roles.value.find(r=>r.id===c.role_id);conversation.value=c.id;page.value='story';mobileNav.value=false;search.value='';await guarded(()=>Promise.all([loadMessages(),loadConversationState()]))}
function mapMessage(row){let metadata={};try{metadata=JSON.parse(row.context_json||'{}')}catch{}return {...metadata,id:row.id,role:row.sender==='user'?'user':'ai',content:row.content,images:imageUrls(row.images_json),turnId:row.turn_id,status:row.status,bookmarked:!!row.bookmarked}}
async function loadMessages(){if(!conversation.value)return;const seq=++selection,c=conversation.value,replyGeneration=replyState.generation,query=search.value.trim();const rows=await json(`/api/conversation/${c}/history?limit=80&search=${encodeURIComponent(query)}`);if(seq!==selection||c!==conversation.value||replyGeneration!==replyState.generation)return;messages.value=rows.map(mapMessage);appliedSearch.value=query;hasOlder.value=rows.length===80;await bottom()}
async function older(){if(!messages.value[0]?.id)return;await guarded(async()=>{const rows=await json(`/api/conversation/${conversation.value}/history?limit=80&before=${messages.value[0].id}&search=${encodeURIComponent(appliedSearch.value)}`);messages.value.unshift(...rows.map(mapMessage));hasOlder.value=rows.length===80})}
async function bottom(){await nextTick();const el=messageList.value?.element();if(el)el.scrollTop=el.scrollHeight}
const { stopSpeech, stop, send, isRecording, cancelRecording, restoreFailedDraft, toggleRecording, readLast, addImages } = useReleaseStream({
  bottom, conversation, generating, guarded, images, installingModels, loadChapters, loadMessages,
  me, messages, model, nativeInfo, offline, offlineModelMissing,
  openAccount: (...args) => openAccount(...args), page, pendingContinuation, pendingDailyIssue,
  proactive, replyState, selected, sendDraftRecovery, speech, stopping, text, toast, webSearch,
})
const { rewind, branch, editMessage, rewrite, bookmark, deleteChapter } = useReleaseHistory({
  askAction, chapters, conversation, guarded, historyBusy, images, loadChapters,
  loadConversationState, loadMessages, messages, openChapter, page, pendingContinuation,
  pendingDailyIssue, send, sendDraftRecovery, stop, text, toast, triggerProactive,
})
const { openAccount, saveModel, testModel, resetModel, exportData, importData, installModels, deleteData } = useReleaseAccount({
  accountNotice, accountOpen, accountTab, askAction, autoRead, cancelRecording, companionOpen,
  conversation, conversationState, dailyStatus, draftRecovery, error, guarded, images,
  initializeCompanion, installingModels, loadLibrary, messages, mobileNav, model, nativeInfo,
  offline, page, pendingContinuation, resetCharacterImport: () => { characterImported = false },
  sendDraftRecovery, showScene, signOut, speech, stop, text, toast, toolsOpen, webSearch,
})
function handleSessionExpired(){
  if(offline||replyState.disposed)return
  if(me.value?.email)credentials.value.email=me.value.email
  draftRecovery.remember({userId:me.value?.id,base:apiBase(),conversationId:conversation.value,roleId:selected.value?.id,text:text.value,images:[...images.value],continuationHistoryId:pendingContinuation.value?.id,dailyIssueDate:pendingDailyIssue.value?.date},replyState.pendingSend)
  replyState.generation++;selection++;replyState.controller?.abort();replyState.controller=null;replyState.pendingSend=null;replyState.proactiveIndex=null
  speech.stop();cancelRecording();locationController?.abort();proactive.detach(false);clearInterval(dailyTimer)
  generating.value=false;stopping.value=false;settleAction();accountOpen.value=false;showScene.value=false;toolsOpen.value=false;companionOpen.value=false;showRoleForm.value=false
  me.value=null;selected.value=null;conversation.value='';messages.value=[];chapters.value=[];roles.value=[];text.value='';images.value=[];page.value='library';characterImported=false
  register.value=false;credentials.value.password='';error.value='';clearNotice()
  authNotice.value='登录状态已失效，请重新登录。未发送的文字和照片暂存在此页面；登录同一账号后可继续，请勿刷新页面。'
  nextTick(()=>document.querySelector('.release-entry input[type="password"]')?.focus())
}
const removeSessionExpired=onSessionExpired(handleSessionExpired)
async function signOut(){
  sendDraftRecovery.clear();draftRecovery.clear();authNotice.value='';text.value='';images.value=[];locationController?.abort()
  await proactive.detach();await stop();clearInterval(dailyTimer);await logout()
  characterImported=false;me.value=null;selected.value=null;conversation.value='';conversationState.value=null
  messages.value=[];chapters.value=[];roles.value=[];page.value='library'
  search.value='';appliedSearch.value='';showSearch.value=false;hasOlder.value=false
  accountOpen.value=false;showScene.value=false;toolsOpen.value=false;companionOpen.value=false;showRoleForm.value=false
  chapterMenuOpen.value=false;mobileNav.value=false;dailyStatus.value={};roleForm.value={};model.value={}
  settleAction();clearNotice();error.value='';draftRecovery.clear();authNotice.value=''
}
function editRole(r){roleForm.value={...r};error.value='';showRoleForm.value=true}
async function saveRole(){await guarded(async()=>{await json(roleForm.value.id?`/api/roles/${roleForm.value.id}`:'/api/roles',roleForm.value,roleForm.value.id?'PUT':'POST');showRoleForm.value=false;await loadLibrary()})}
async function removeRole(){if(!await askAction({title:'删除角色',description:'删除这个自建角色。已有故事的角色需要先处理其故事，删除无法撤销。',confirmLabel:'删除角色'}))return;await guarded(async()=>{await json(`/api/roles/${roleForm.value.id}`,undefined,'DELETE');showRoleForm.value=false;await loadLibrary()})}
useReleaseStartup({
  authNotice, booting, connected, desktop, error, initializeCompanion, loadLibrary, me,
  nativeInfo, nativeTimer, offline, replyState, server, toast,
})
onBeforeUnmount(()=>{replyState.disposed=true;removeSessionExpired();draftRecovery.clear();sendDraftRecovery.clear();clearNotice();settleAction();locationController?.abort();proactive.detach();speech.close();stop();clearEmailTimer();clearInterval(nativeTimer.value);clearInterval(dailyTimer)})
</script>

<style scoped>
.release-settings-fields{border:0;padding:0;margin:0;min-width:0}
.release-unsent-drafts{padding:12px 14px;margin-bottom:12px;border:1px solid var(--r-line);border-radius:10px;background:var(--r-paper)}
.release-unsent-drafts p{margin:0 0 8px;font-size:12px;line-height:1.7;color:var(--r-muted)}
.release-unsent-drafts>div{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:8px;font-size:12px}
.release-unsent-drafts span{overflow-wrap:anywhere;min-width:0}
.release-unsent-drafts button{flex-shrink:0;font-size:12px}
</style>
