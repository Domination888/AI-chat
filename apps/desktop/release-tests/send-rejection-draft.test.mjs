import { releaseAppSource, runReleaseSnippet } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {once} from 'node:events'
import {ref,watch,effectScope,nextTick} from 'vue'
import {useSendDraftRecovery} from '../src/release/sendDraftRecovery.mjs'
import {createSessionDraftRecovery} from '../src/release/sessionDraft.mjs'

const source=releaseAppSource()
const extract=(start,end)=>source.slice(source.indexOf(start),source.indexOf(end,source.indexOf(start)))
const code=[
  extract('watch(conversation,()=>{conversationState.value=null;stateRequest++}','\nasync function refreshStory()'),
  extract('async function send(audioPayload)','\nconst {isRecording'),
  extract('function restoreFailedDraft(id){','\nasync function promptModelSetup()'),
  extract('async function loadMessages(){','\nasync function older('),
  extract('async function stop(cancelCapture=true){','\nasync function send('),
  extract('async function openChapter(c){','\nfunction mapMessage('),
  extract('function handleSessionExpired(){','\nconst removeSessionExpired='),
  extract('async function signOut(){','\nfunction editRole('),
  extract('async function connectServer(){','\nasync function sendCode()'),
].join('\n')
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
function fixture(t){
  const scope=effectScope(),requests=[],notices=[],history=ref([]),base=ref('https://one.test')
  t.after(()=>scope.stop())
  let respond=async()=>new Response('',{status:200})
  const context={pendingContinuation:ref(null),pendingDailyIssue:ref(null),watch,nextTick,offline:false,disposed:false,installingModels:ref(false),offlineModelMissing:ref(false),text:ref('原来的消息'),images:ref(['data:image/png;base64,old']),me:ref({id:4,email:'one@test.example'}),selected:ref({id:2}),roles:ref([{id:2},{id:9}]),conversation:ref('chapter-a'),conversationState:ref({mode:'companion'}),page:ref('story'),messages:ref([]),chapters:ref([{id:'chapter-a',role_id:2}]),stateRequest:0,generation:0,selection:0,pendingSend:null,controller:null,proactiveIndex:null,search:ref(''),appliedSearch:ref(''),hasOlder:ref(false),generating:ref(false),stopping:ref(false),webSearch:ref(false),speech:{unlock:async()=>{},addText:()=>{},stop:()=>{}},proactive:{activity:async()=>{},detach:async()=>{}},stopSpeech:()=>{},cancelRecording:()=>{},bottom:async()=>{},AbortController,apiBase:()=>base.value,request:async(path,init)=>{requests.push({path,body:JSON.parse(init.body)});return respond(path,init)},readSse:async function*(){yield {event:'done',payload:{}}},json:async path=>path.includes('/history?')?[...history.value]:path==='/api/health'?{mode:'cloud'}:{state:{mode:'companion'}},mapMessage:row=>row,loadChapters:async()=>{},guarded:fn=>fn(),isSessionExpired:error=>error.code==='SESSION_EXPIRED',toast:message=>notices.push(message),document:{querySelector:()=>null},draftRecovery:createSessionDraftRecovery(),locationController:{abort:()=>{}},clearInterval:()=>{},dailyTimer:1,settleAction:()=>{},clearNotice:()=>{},characterImported:true,credentials:ref({}),register:ref(false),error:ref(''),authNotice:ref(''),dailyStatus:ref({}),roleForm:ref({}),model:ref({}),logout:async()=>{},server:ref('https://two.test'),connected:ref(false),connect:async value=>{base.value=value}}
  for(const key of ['accountOpen','showScene','toolsOpen','companionOpen','showRoleForm','chapterMenuOpen','mobileNav','showSearch'])context[key]=ref(false)
  let controller
  scope.run(()=>{
    context.sendDraftRecovery=useSendDraftRecovery({scope:()=>({userId:context.me.value?.id,base:base.value,conversationId:context.conversation.value,inStory:context.page.value==='story'}),readDraft:()=>({text:context.text.value,images:context.images.value,dailyIssueDate:context.pendingDailyIssue.value?.date}),writeDraft:draft=>{context.text.value=draft.text;context.images.value=[...draft.images];context.pendingDailyIssue.value=draft.dailyIssueDate?{conversation:context.conversation.value,date:draft.dailyIssueDate}:null}})
    controller=runReleaseSnippet(`${code}\n;({send,restoreFailedDraft,openChapter,signOut,connectServer,handleSessionExpired})`,context)
  })
  return {context,requests,notices,history,base,drafts:context.sendDraftRecovery.visible,respond:fn=>{respond=fn},...controller}
}
const rejected=()=>new Response(JSON.stringify({message:'发送过于频繁，请稍后重试'}),{status:429,headers:{'Content-Type':'application/json','Retry-After':'60'}})
async function waitForRequest(f){const started=deferred(),result=deferred();f.respond(()=>{started.resolve();return result.promise});const sending=f.send();await started.promise;return {sending,...result}}

test('a real HTTP 429 restores unsent text and photos without resending or inventing history',async t=>{
  let received=0
  const server=createServer((req,res)=>{received++;res.writeHead(429,{'Content-Type':'application/json','Retry-After':'60'});res.end(JSON.stringify({message:'发送过于频繁，请稍后重试'}))})
  server.listen(0,'127.0.0.1');await once(server,'listening')
  t.after(()=>new Promise(resolve=>server.close(resolve)))
  const f=fixture(t),origin=`http://127.0.0.1:${server.address().port}`
  f.respond((path,init)=>fetch(origin+path,init))
  await f.send();await nextTick()
  assert.equal(received,1)
  assert.equal(f.context.text.value,'原来的消息')
  assert.deepEqual([...f.context.images.value],['data:image/png;base64,old'])
  assert.equal(f.context.messages.value.length,0)
  assert.equal(f.drafts.value.length,0)
  assert.ok(f.notices.some(message=>message.includes('内容已放回输入框')))
})

test('network failure preserves the draft and removes optimistic messages even when history cannot refresh',async t=>{
  const f=fixture(t)
  f.respond(async()=>{throw new TypeError('Failed to fetch')})
  f.context.json=async()=>{throw new TypeError('Failed to fetch')}
  await f.send()
  assert.equal(f.context.text.value,'原来的消息')
  assert.deepEqual([...f.context.images.value],['data:image/png;base64,old'])
  assert.equal(f.context.messages.value.length,0)
  assert.equal(f.requests.length,1)
})

test('new writing is never overwritten and restoring the rejected draft keeps both messages and photo sets',async t=>{
  const f=fixture(t),pending=await waitForRequest(f)
  f.context.text.value='等待期间写下的新消息';f.context.images.value=['data:image/png;base64,new']
  pending.resolve(rejected());await pending.sending;await nextTick()
  assert.equal(f.context.text.value,'等待期间写下的新消息')
  assert.deepEqual([...f.context.images.value],['data:image/png;base64,new'])
  assert.equal(f.drafts.value.length,1)
  const id=f.drafts.value[0].id
  f.restoreFailedDraft(id);await nextTick()
  assert.equal(f.context.text.value,'原来的消息')
  assert.deepEqual([...f.context.images.value],['data:image/png;base64,old'])
  assert.equal(f.drafts.value[0].text,'等待期间写下的新消息')
  assert.deepEqual([...f.drafts.value[0].images],['data:image/png;base64,new'])
  f.restoreFailedDraft(id);await nextTick()
  assert.equal(f.context.text.value,'等待期间写下的新消息')
  assert.equal(f.drafts.value[0].text,'原来的消息')
  assert.equal(f.requests.length,1)
  assert.ok(f.notices.some(message=>message.includes('刚才编辑的草稿也已暂存')))
})

test('an accepted stream error keeps the persisted retry turn and does not restore the sent message as a draft',async t=>{
  const f=fixture(t)
  f.context.readSse=async function*(){
    yield {event:'text',payload:{text:'半句回复'}}
    f.history.value=[{id:1,role:'user',content:'原来的消息'},{id:2,role:'ai',content:'半句回复',status:'failed'}]
    yield {event:'error',payload:{message:'模型连接中断'}}
  }
  await f.send();await nextTick()
  assert.equal(f.context.text.value,'')
  assert.equal(f.context.images.value.length,0)
  assert.equal(f.drafts.value.length,0)
  assert.equal(f.context.messages.value.length,2)
  assert.equal(f.context.messages.value[1].status,'failed')
  assert.equal(f.requests.length,1)
})

test('a rejected request arriving after chapter navigation cannot populate the new chapter composer',async t=>{
  const f=fixture(t),pending=await waitForRequest(f)
  await f.openChapter({id:'chapter-b',role_id:9})
  f.context.text.value='新篇章的草稿';f.context.images.value=[]
  pending.reject(new TypeError('network disconnected'));await pending.sending;await nextTick()
  assert.equal(f.context.conversation.value,'chapter-b')
  assert.equal(f.context.text.value,'新篇章的草稿')
  assert.equal(f.drafts.value.length,0)
  await f.openChapter({id:'chapter-a',role_id:2})
  assert.equal(f.drafts.value.length,1)
  assert.equal(f.drafts.value[0].text,'原来的消息')
})

test('explicit sign-out, another account, and another service discard rejected drafts and invalidate late responses',async t=>{
  for(const change of ['sign-out','account','service'])await t.test(change,async t=>{
    const f=fixture(t),pending=await waitForRequest(f)
    if(change==='sign-out')await f.signOut()
    if(change==='account')f.context.me.value={id:9}
    if(change==='service')await f.connectServer()
    f.context.text.value='新环境的草稿';f.context.images.value=[]
    pending.reject(new TypeError('late rejected response'));await pending.sending;await nextTick()
    assert.equal(f.context.text.value,'新环境的草稿')
    assert.equal(f.drafts.value.length,0)
  })
})

test('session expiry delegates an unaccepted send to same-account recovery exactly once',async t=>{
  const f=fixture(t)
  f.respond(async()=>{f.handleSessionExpired();throw Object.assign(new Error('请重新登录'),{code:'SESSION_EXPIRED'})})
  await f.send();await nextTick()
  assert.equal(f.context.me.value,null)
  assert.equal(f.context.text.value,'')
  assert.equal(f.drafts.value.length,0)
  const saved=f.context.draftRecovery.take(4,'https://one.test')
  assert.equal(saved.text,'原来的消息')
  assert.deepEqual(saved.images,['data:image/png;base64,old'])
  assert.equal(f.context.draftRecovery.take(4,'https://one.test'),null)
})

test('queued rejected drafts remain hidden during expiry and return only for the same account',async t=>{
  const f=fixture(t),pending=await waitForRequest(f)
  f.context.text.value='下一条草稿'
  pending.resolve(rejected());await pending.sending
  assert.equal(f.drafts.value.length,1)
  f.context.me.value=null
  assert.equal(f.drafts.value.length,0)
  f.context.me.value={id:4}
  assert.equal(f.drafts.value.length,1)
  f.context.me.value={id:9}
  assert.equal(f.drafts.value.length,0)
  f.context.me.value={id:4}
  assert.equal(f.drafts.value.length,0)
})

test('explicit sign-out also clears queued drafts when the same account later returns',async t=>{
  const f=fixture(t),pending=await waitForRequest(f)
  f.context.text.value='下一条草稿'
  pending.resolve(rejected());await pending.sending
  assert.equal(f.drafts.value.length,1)
  await f.signOut()
  f.context.me.value={id:4};f.context.conversation.value='chapter-a';f.context.page.value='story'
  assert.equal(f.drafts.value.length,0)
  assert.equal(f.context.text.value,'')
})


test('SSE rejection before message persistence restores text and photos after HTTP 200',async t=>{
  const f=fixture(t)
  f.context.readSse=async function*(){yield {event:'error',payload:{message:'图片内容不合法',accepted:false}}}
  await f.send()
  assert.equal(f.context.text.value,'原来的消息')
  assert.deepEqual([...f.context.images.value],['data:image/png;base64,old'])
  assert.equal(f.requests.length,1)
  assert.ok(f.notices.some(message=>message.includes('内容已放回输入框')))
})

test('SSE model failure after a saved message does not create a duplicate draft',async t=>{
  const f=fixture(t)
  f.context.readSse=async function*(){yield {event:'error',payload:{message:'模型暂不可用',accepted:true}}}
  await f.send()
  assert.equal(f.context.text.value,'')
  assert.deepEqual([...f.context.images.value],[])
  assert.equal(f.requests.length,1)
  assert.equal(f.drafts.value.length,0)
})

test('selected issue survives edited wording and rejected SSE but is consumed on acceptance',async t=>{
 const f=fixture(t);f.context.pendingDailyIssue.value={conversation:f.context.conversation.value,date:'2026-10-30'};f.context.text.value='Jules来自哪一期？';
 f.context.readSse=async function*(){yield {event:'error',payload:{message:'rejected',accepted:false}}};await f.send();
 assert.equal(f.requests[0].body.dailyIssueDate,'2026-10-30');assert.equal(f.context.pendingDailyIssue.value.date,'2026-10-30');assert.equal(f.context.text.value,'Jules来自哪一期？');
 f.context.readSse=async function*(){yield {event:'done',payload:{}}};await f.send();assert.equal(f.context.pendingDailyIssue.value,null);
})
test('session expiry retains selected issue with unsent text',()=>{
 const store=createSessionDraftRecovery();store.remember({userId:8,base:'local',conversationId:'c',text:'edited',images:[],dailyIssueDate:'2026-10-30'},null);assert.equal(store.take(8,'local').dailyIssueDate,'2026-10-30');
})

test('old accepted request cannot clear a newly selected same-date draft',async t=>{
 const f=fixture(t);f.context.pendingDailyIssue.value={conversation:f.context.conversation.value,date:'2026-10-30'};
 const request=await waitForRequest(f);const newer={conversation:f.context.conversation.value,date:'2026-10-30'};f.context.pendingDailyIssue.value=newer;const selected=f.context.pendingDailyIssue.value;
 request.resolve(new Response('',{status:200}));await request.sending;assert.equal(f.context.pendingDailyIssue.value,selected);
})
test('replay finds issue from adjacent assistant metadata before rewind removes it',()=>{
 const user={role:'user',content:'edited question'},reply={role:'ai',aiDaily:{date:'2026-10-30'}};const messages={value:[user,reply]};
 const helper=extract('function dailyIssueForReplay(m){','async function editMessage(m){');const get=runReleaseSnippet(helper+';dailyIssueForReplay',{messages});
 const date=get(user);messages.value=[];assert.equal(date,'2026-10-30');assert.equal(get({role:'user'}),null);
})
