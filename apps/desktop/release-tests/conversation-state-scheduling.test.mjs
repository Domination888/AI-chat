import { releaseAppSource } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import {ref,watch,effectScope,nextTick} from 'vue'
import {createSessionDraftRecovery} from '../src/release/sessionDraft.mjs'

const source=releaseAppSource()
const stateCode=source.slice(source.indexOf('watch(conversation,()=>{conversationState.value=null;stateRequest++}'),source.indexOf('\nasync function refreshStory()'))
const authCode=source.slice(source.indexOf('async function authenticate(){'),source.indexOf('\nasync function loadChapters()'))
const chapterCode=source.slice(source.indexOf('async function openChapter(c){'),source.indexOf('\nfunction mapMessage('))
const chapterA={id:'chapter-a',role_id:2},chapterB={id:'chapter-b',role_id:2}
function deferred(){let resolve;const promise=new Promise(r=>{resolve=r});return {promise,resolve}}
function fixture(t){
  const scope=effectScope(),requests=[]
  t.after(()=>scope.stop())
  const context={pendingContinuation:ref(null),pendingDailyIssue:ref(null),watch,conversation:ref(''),conversationState:ref(null),stateRequest:0,selected:ref(null),roles:ref([{id:2}]),page:ref('library'),mobileNav:ref(false),search:ref(''),guarded:fn=>fn(),stop:async()=>{},loadMessages:async()=>{},me:ref(null),register:ref(false),credentials:ref({password:'test-only-password'}),text:ref(''),images:ref([]),chapters:ref([chapterA,chapterB]),authNotice:ref('会话已失效'),draftRecovery:createSessionDraftRecovery(),apiBase:()=> 'https://story.test',acceptSession:async session=>session.user,loadLibrary:async()=>{},initializeCompanion:async()=>{},toast:()=>{}}
  context.json=async path=>{requests.push(path);if(path==='/api/v1/auth/login')return {user:{id:4}};return {state:{mode:'companion',location:path}}}
  let controller
  scope.run(()=>{controller=vm.runInNewContext(`${stateCode}\n${authCode}\n${chapterCode}\n;({loadConversationState,authenticate,openChapter})`,context)})
  return {context,requests,...controller}
}

test('opening an existing chapter retains its loaded mode after real Vue watchers flush',async t=>{
  const f=fixture(t)
  await f.openChapter(chapterA)
  await nextTick()
  assert.equal(f.context.conversation.value,'chapter-a')
  assert.equal(f.context.conversationState.value?.mode,'companion')
  assert.equal(f.requests.filter(path=>path==='/api/rp/chapter-a').length,1)
})

test('same-account re-login restores the draft and current mode with the actual watcher schedule',async t=>{
  const f=fixture(t)
  f.context.draftRecovery.remember({userId:4,base:'https://story.test',conversationId:'chapter-a',roleId:2,text:'还没有发送的文字',images:['data:image/png;base64,test']})
  await f.authenticate()
  await nextTick()
  assert.equal(f.context.page.value,'story')
  assert.equal(f.context.conversation.value,'chapter-a')
  assert.equal(f.context.text.value,'还没有发送的文字')
  assert.deepEqual([...f.context.images.value],['data:image/png;base64,test'])
  assert.equal(f.context.conversationState.value?.mode,'companion')
  assert.equal(f.context.authNotice.value,'')
})

test('a late response cannot replace the mode after rapidly selecting another chapter',async t=>{
  const f=fixture(t),first=deferred(),started=deferred()
  f.context.json=path=>{if(path==='/api/rp/chapter-a'){started.resolve();return first.promise}return Promise.resolve({state:{mode:'companion',location:'新篇章'}})}
  const openingA=f.openChapter(chapterA)
  await started.promise
  await f.openChapter(chapterB)
  await nextTick()
  assert.equal(f.context.conversationState.value?.location,'新篇章')
  first.resolve({state:{mode:'story',location:'旧篇章'}})
  await openingA
  assert.equal(f.context.conversationState.value?.location,'新篇章')
})

test('returning to the same chapter still rejects the earlier visit response',async t=>{
  const f=fixture(t),first=deferred(),started=deferred()
  let visits=0
  f.context.json=path=>{
    if(path==='/api/rp/chapter-a'&&++visits===1){started.resolve();return first.promise}
    return Promise.resolve({state:{mode:'companion',location:'重新选择后的场景'}})
  }
  const earlierVisit=f.openChapter(chapterA)
  await started.promise
  await f.openChapter(chapterB)
  await f.openChapter(chapterA)
  await nextTick()
  first.resolve({state:{mode:'story',location:'第一次访问的旧场景'}})
  await earlierVisit
  assert.equal(f.context.conversation.value,'chapter-a')
  assert.equal(f.context.conversationState.value?.location,'重新选择后的场景')
})
