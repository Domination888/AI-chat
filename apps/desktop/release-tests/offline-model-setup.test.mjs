import { releaseAppSource, runReleaseSnippet } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import {ref,computed} from 'vue'
import {localChatBlocked} from '../src/release/localServices.mjs'
import {useSendDraftRecovery} from '../src/release/sendDraftRecovery.mjs'

const source=releaseAppSource()
const extract=(start,end)=>source.slice(source.indexOf(start),source.indexOf(end,source.indexOf(start)))
const setupCode=extract('const installingModels=ref(false)','\nconst actionDialog=')
const sendCode=extract('async function send(audioPayload)','\nconst {isRecording')
const preparationCode=extract('async function promptModelSetup()','\nasync function readLast()')
const accountCode=extract('async function openAccount(','\nasync function saveModel()')
const installCode=extract('async function installModels(){','\nasync function deleteData()')
const guardCode=extract('async function guarded(fn)','\nasync function connectServer()')
const keyboardCode=extract('function handleUiKeydown(event){','\nonMounted(()=>document.addEventListener')
function deferred(){let resolve;const promise=new Promise(r=>{resolve=r});return {promise,resolve}}
function fixture({offline=true,custom=false,native={modelState:'missing'},pictures=[]}={}){
  const calls=[]
  const context={localChatBlocked,pendingContinuation:ref(null),pendingDailyIssue:ref(null),ref,computed,offline,model:ref({custom}),nativeInfo:ref(native),text:ref('还没发送的文字'),images:ref(pictures),conversation:ref('chapter'),selected:ref({id:2}),me:ref({id:4}),messages:ref([]),generation:0,pendingSend:null,controller:null,generating:ref(false),webSearch:ref(false),speech:{unlock:async()=>{},addText:()=>{}},proactive:{activity:async()=>{}},stop:async()=>calls.push('stop'),bottom:async()=>{},AbortController,request:async(path,init)=>{calls.push({path,body:JSON.parse(init.body)});return {ok:true,body:{}}},readSse:async function*(){yield {event:'done',payload:{}}},loadMessages:async()=>{},loadChapters:async()=>{},isSessionExpired:()=>false,clearNotice:()=>{},busy:ref(false),error:ref(''),accountOpen:ref(true),accountNotice:ref(''),accountTab:ref('model'),mobileNav:ref(false),isRecording:ref(false),startRecording:async()=>calls.push('microphone'),stopRecording:()=>calls.push('finish-recording'),runtimeInfo:{apiBase:'http://127.0.0.1:50002',modelState:'ready'},initialize:async()=>calls.push('initialize'),window:{storyDesktop:{installModelPack:async()=>({installed:true})}},actionDialog:ref(null),document:{querySelector:()=>assert.fail('installation Escape must not close or navigate a dialog')}}
  context.json=async path=>{calls.push({path});return {custom}}
  context.toast=message=>{calls.push({notice:message});context.accountNotice.value=message}
  context.sendDraftRecovery=useSendDraftRecovery({scope:()=>({userId:context.me.value?.id,base:'test-service',conversationId:context.conversation.value,inStory:true}),readDraft:()=>({text:context.text.value,images:context.images.value}),writeDraft:draft=>{context.text.value=draft.text;context.images.value=draft.images}})
  const controller=runReleaseSnippet(`${setupCode}\n${guardCode}\n${accountCode}\n${sendCode}\n${preparationCode}\n${installCode}\n${keyboardCode}\n;({installingModels,offlineModelMissing,sendDisabled,send,toggleRecording,installModels,handleUiKeydown})`,context)
  return {context,calls,...controller}
}

test('an explicitly missing offline model guides setup without losing text or creating a failed turn',async()=>{
  const f=fixture()
  assert.equal(f.sendDisabled.value,true)
  await f.send()
  assert.equal(f.context.text.value,'还没发送的文字')
  assert.equal(f.context.messages.value.length,0)
  assert.equal(f.context.accountOpen.value,true)
  assert.equal(f.context.accountTab.value,'model')
  assert.ok(!f.calls.some(call=>call.path==='/api/chat'))
  assert.ok(f.calls.some(call=>call.notice?.includes('文字会保留')))
})

test('custom, external, ready, unknown and commercial connections are not mistaken for a missing local model',async t=>{
  for(const options of [{custom:true},{native:{modelState:'missing',externalModels:true}},{native:{modelState:'ready'}},{native:{}},{offline:false}])await t.test(JSON.stringify(options),async()=>{
    const f=fixture(options)
    assert.equal(f.offlineModelMissing.value,false)
    assert.equal(f.sendDisabled.value,false)
    await f.send()
    assert.equal(f.calls.filter(call=>call.path==='/api/chat').length,1)
  })
})

test('photos remain sendable for archiving while no offline chat model is installed',async()=>{
  const f=fixture({pictures:['data:image/png;base64,test']})
  assert.equal(f.offlineModelMissing.value,true)
  assert.equal(f.sendDisabled.value,false)
  await f.send()
  const chat=f.calls.find(call=>call.path==='/api/chat')
  assert.deepEqual(chat.body.images,['data:image/png;base64,test'])
})

test('voice setup guidance does not request the microphone before the missing model is prepared',async()=>{
  const f=fixture()
  await f.toggleRecording()
  assert.ok(!f.calls.includes('microphone'))
  assert.ok(f.calls.some(call=>call.notice?.includes('语音服务')))
})

test('installation stays locked until the new API binding is initialized and settings are refreshed',async()=>{
  const f=fixture(),selection=deferred(),binding=deferred(),selecting=deferred(),initializing=deferred()
  f.context.window.storyDesktop.installModelPack=()=>{selecting.resolve();return selection.promise}
  f.context.initialize=()=>{initializing.resolve();return binding.promise}
  const installing=f.installModels()
  assert.equal(f.installingModels.value,true)
  await selecting.promise
  await f.send()
  let prevented=false
  f.handleUiKeydown({key:'Escape',preventDefault:()=>{prevented=true}})
  assert.equal(prevented,true)
  assert.equal(f.context.accountOpen.value,true)
  assert.equal(f.context.text.value,'还没发送的文字')
  assert.equal(f.calls.filter(call=>call.path==='/api/chat').length,0)
  selection.resolve({installed:true});await initializing.promise
  assert.equal(f.installingModels.value,true)
  assert.equal(f.calls.filter(call=>call.path==='/api/v1/me/model-connection').length,0)
  binding.resolve();await installing
  assert.equal(f.installingModels.value,false)
  assert.equal(f.context.nativeInfo.value.apiBase,'http://127.0.0.1:50002')
  assert.equal(f.calls.filter(call=>call.path==='/api/v1/me/model-connection').length,1)
})

test('cancelling folder selection releases the installation state and permits another attempt',async()=>{
  const f=fixture()
  let selections=0
  f.context.window.storyDesktop.installModelPack=async()=>{selections++;return {installed:false}}
  await f.installModels();await f.installModels()
  assert.equal(selections,2)
  assert.equal(f.installingModels.value,false)
  assert.equal(f.context.accountNotice.value,'已取消模型包导入')
  assert.equal(f.context.text.value,'还没发送的文字')
})

test('failed activation rebinds a restored backend, preserves the real error, and permits retry',async()=>{
  const f=fixture()
  f.context.window.storyDesktop.installModelPack=async()=>{throw new Error('模型校验失败')}
  await f.installModels()
  assert.equal(f.installingModels.value,false)
  assert.equal(f.context.error.value,'模型校验失败')
  assert.ok(f.calls.includes('initialize'))
  assert.equal(f.context.nativeInfo.value.apiBase,'http://127.0.0.1:50002')
  f.context.window.storyDesktop.installModelPack=async()=>({installed:false})
  await f.installModels()
  assert.equal(f.installingModels.value,false)
  assert.equal(f.context.accountNotice.value,'已取消模型包导入')
})
