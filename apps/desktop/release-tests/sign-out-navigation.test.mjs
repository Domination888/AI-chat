import { releaseAppSource, runReleaseSnippet } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import {ref,watch,effectScope,nextTick} from 'vue'
import {createSessionDraftRecovery} from '../src/release/sessionDraft.mjs'
import {useSendDraftRecovery} from '../src/release/sendDraftRecovery.mjs'

const source=releaseAppSource()
const extract=(start,end)=>source.slice(source.indexOf(start),source.indexOf(end,source.indexOf(start)))
const code=[
  extract('watch(conversation,()=>{conversationState.value=null;stateRequest++}','\nasync function refreshStory()'),
  extract('async function authenticate(){','\nasync function loadChapters()'),
  extract('async function openChapter(c){','\nfunction mapMessage('),
  extract('async function loadMessages(){','\nasync function older('),
  extract('async function stop(cancelCapture=true){','\nasync function send('),
  extract('function handleSessionExpired(){','\nconst removeSessionExpired='),
  extract('async function signOut(){','\nfunction editRole('),
].join('\n')
const oldChapter={id:'chapter-a',role_id:2,title:'旧篇章'},newChapter={id:'chapter-b',role_id:9,title:'新篇章'}
function deferred(){let resolve;const promise=new Promise(r=>{resolve=r});return {promise,resolve}}
function fixture(t){
  const scope=effectScope(),calls=[];t.after(()=>scope.stop())
  let loginUser=4
  const records={4:{role:{id:2,name:'旧角色'},chapter:oldChapter},9:{role:{id:9,name:'新角色'},chapter:newChapter}}
  const context={pendingContinuation:ref(null),pendingDailyIssue:ref(null),watch,nextTick,offline:false,disposed:false,generation:0,selection:0,stateRequest:0,controller:null,pendingSend:null,proactiveIndex:null,conversation:ref('chapter-a'),conversationState:ref({mode:'companion'}),selected:ref(records[4].role),roles:ref([records[4].role]),chapters:ref([oldChapter]),messages:ref([{id:1,content:'旧故事'}]),page:ref('story'),me:ref({id:4,email:'old@test.example'}),text:ref('未发送的文字'),images:ref(['data:image/png;base64,test']),search:ref('旧搜索'),appliedSearch:ref('旧搜索'),hasOlder:ref(true),dailyStatus:ref({unread:true}),roleForm:ref({name:'旧角色草稿'}),model:ref({custom:true}),authNotice:ref(''),error:ref('旧提示'),credentials:ref({password:'test-only-password'}),register:ref(false),draftRecovery:createSessionDraftRecovery(),apiBase:()=> 'https://story.test',acceptSession:async session=>session.user,guarded:fn=>fn(),initializeCompanion:async()=>{},bottom:async()=>{},mapMessage:row=>row,clearInterval:()=>{},dailyTimer:1,characterImported:true,speech:{stop:()=>{}},stopSpeech:()=>{},cancelRecording:()=>calls.push('cancel-recording'),locationController:{abort:()=>{}},proactive:{detach:async()=>{}},logout:async()=>calls.push('logout'),clearNotice:()=>{},settleAction:()=>{},toast:()=>{},document:{querySelector:()=>null}}
  for(const key of ['generating','stopping'])context[key]=ref(false)
  for(const key of ['showSearch','accountOpen','showScene','toolsOpen','companionOpen','showRoleForm','chapterMenuOpen','mobileNav'])context[key]=ref(true)
  context.loadLibrary=async()=>{const record=records[context.me.value.id];context.roles.value=[record.role];context.chapters.value=[record.chapter]}
  context.json=async path=>{calls.push(path);if(path==='/api/v1/auth/login')return {user:{id:loginUser}};if(path.startsWith('/api/conversation/'))return [{id:22,content:`${context.me.value.id} 的故事`}];return {state:{mode:'companion'}}}
  let controller
  scope.run(()=>{context.sendDraftRecovery=useSendDraftRecovery({scope:()=>({userId:context.me.value?.id,base:context.apiBase(),conversationId:context.conversation.value,inStory:context.page.value==='story'}),readDraft:()=>({text:context.text.value,images:context.images.value}),writeDraft:draft=>{context.text.value=draft.text;context.images.value=draft.images}})})
  scope.run(()=>{controller=runReleaseSnippet(`${code}\n;({signOut,authenticate,openChapter,loadMessages,loadConversationState,handleSessionExpired})`,context)})
  return {context,calls,nextUser:id=>{loginUser=id},...controller}
}

test('explicit sign-out clears the active story and same-account login starts at the library',async t=>{
  const f=fixture(t)
  await f.signOut();await nextTick()
  assert.equal(f.context.me.value,null)
  assert.equal(f.context.conversation.value,'')
  assert.equal(f.context.selected.value,null)
  assert.equal(f.context.conversationState.value,null)
  assert.equal(f.context.roles.value.length,0)
  assert.equal(f.context.search.value,'')
  assert.equal(f.context.appliedSearch.value,'')
  assert.equal(f.context.showSearch.value,false)
  assert.equal(f.context.showScene.value,false)
  assert.equal(f.context.text.value,'')
  assert.equal(f.context.draftRecovery.take(4,'https://story.test'),null)
  await f.authenticate();await nextTick()
  assert.equal(f.context.me.value.id,4)
  assert.equal(f.context.page.value,'library')
  assert.equal(f.context.conversation.value,'')
  assert.equal(f.context.selected.value,null)
  await f.openChapter(oldChapter)
  assert.equal(f.context.messages.value[0].content,'4 的故事')
  assert.equal(f.context.conversationState.value.mode,'companion')
})

test('another account login cannot display the prior role or late history and state responses',async t=>{
  const f=fixture(t),history=deferred(),state=deferred(),request=f.context.json
  f.context.json=path=>{if(path.startsWith('/api/conversation/chapter-a/'))return history.promise;if(path==='/api/rp/chapter-a')return state.promise;return request(path)}
  const oldHistory=f.loadMessages(),oldState=f.loadConversationState()
  await f.signOut();f.nextUser(9);await f.authenticate()
  history.resolve([{id:1,content:'前一账号的旧故事'}]);state.resolve({state:{mode:'story',location:'前一账号的场景'}})
  await Promise.all([oldHistory,oldState]);await nextTick()
  assert.equal(f.context.me.value.id,9)
  assert.equal(f.context.page.value,'library')
  assert.equal(f.context.conversation.value,'')
  assert.equal(f.context.selected.value,null)
  assert.equal(f.context.messages.value.length,0)
  assert.equal(f.context.conversationState.value,null)
  assert.equal(f.context.roles.value.length,1)
  assert.equal(f.context.roles.value[0].name,'新角色')
  assert.equal(f.context.chapters.value[0].id,'chapter-b')
})

test('forced session expiry still restores the same-account draft and loads its existing story',async t=>{
  const f=fixture(t)
  f.handleSessionExpired()
  await f.authenticate();await nextTick()
  assert.equal(f.context.page.value,'story')
  assert.equal(f.context.conversation.value,'chapter-a')
  assert.equal(f.context.selected.value.name,'旧角色')
  assert.equal(f.context.text.value,'未发送的文字')
  assert.deepEqual([...f.context.images.value],['data:image/png;base64,test'])
  assert.equal(f.context.messages.value[0].content,'4 的故事')
  assert.equal(f.context.conversationState.value.mode,'companion')
})
