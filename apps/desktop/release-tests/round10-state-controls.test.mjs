import { releaseAppSource } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import * as Vue from 'vue'
import {compile} from '@vue/compiler-dom'
import {parse} from '@vue/compiler-sfc'
import {regenerationAction,manualContinuationAvailable} from '../src/release/messageActions.mjs'
import {allLive2dMotions,allLive2dExpressions} from '../src/live2d/live2d-options.js'

const source=releaseAppSource()
const stateCode=source.slice(source.indexOf('watch(conversation,()=>{conversationState.value=null;stateRequest++}'),source.indexOf('\nasync function refreshStory()'))
const historyCode=source.slice(source.indexOf("const rewindScope="),source.indexOf('\nasync function bookmark('))
const header=source.split('\n').find(line=>line.includes('<div class="release-story-mode">'))
const renderHeader=new Function('Vue',compile(header,{mode:'function'}).code)(Vue)
function walk(v,visit){if(!v||typeof v!=='object')return;visit(v);if(Array.isArray(v.children))v.children.forEach(c=>walk(c,visit))}
function words(v){if(typeof v==='string')return v;if(!v||typeof v!=='object')return '';return Array.isArray(v.children)?v.children.map(words).join(''):String(v.children||'')}
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve}}
function fixture(t){
  const scope=Vue.effectScope(),events=[],errors=[]
  t.after(()=>scope.stop())
  const restored={mode:'story',paused:false,location:'柳堤茶亭',commitments:['周日修望远镜']}
  const c={watch:Vue.watch,conversation:Vue.ref('a'),conversationState:Vue.ref({mode:'companion',paused:true}),stateRequest:0,historyBusy:Vue.ref(false),messages:Vue.ref([{id:10,role:'user',content:'原问题',images:[]},{id:11,role:'ai',status:'complete',content:'原回答'}]),text:Vue.ref(''),images:Vue.ref([]),pendingContinuation:Vue.ref(null),pendingDailyIssue:Vue.ref(null),regenerationAction,manualContinuationAvailable,stop:async()=>{},loadMessages:async()=>events.push('messages'),loadChapters:async()=>events.push('chapters'),apiBase:()=> 'https://test.invalid',prepareReplayImages:async images=>images,askAction:async()=> '修改的问题',toast:()=>{},nextTick:Vue.nextTick,document:{querySelector:()=>null},send:async()=>{events.push('send');assert.equal(c.conversationState.value.mode,'story')},guarded:async fn=>{try{return await fn()}catch(e){errors.push(e.message)}},json:async(path,body,method)=>{events.push({path,method});return method==='POST'?{}:{state:restored}}}
  let actions
  scope.run(()=>{actions=vm.runInNewContext(`${stateCode}\n${historyCode}\n;({applyRewind,rewind,editMessage,rewrite,loadConversationState})`,c)})
  return {c,events,errors,restored,...actions}
}

for(const action of ['rewind','edit','rewrite','retry'])test(`${action} refreshes actual state before draft/replay and compiled header reflects restored story`,async t=>{
  const f=fixture(t)
  if(action==='retry')f.c.messages.value[1].status='failed'
  if(action==='rewind')await f.rewind(f.c.messages.value[1])
  else if(action==='edit')await f.editMessage(f.c.messages.value[0])
  else await f.rewrite(1)
  await Vue.nextTick()
  assert.deepEqual({...f.c.conversationState.value},f.restored)
  const context={conversationState:f.c.conversationState.value,modeBusy:false,switchConversationMode:()=>{},openCompanion:()=>{},openScene:()=>{}}
  const header=renderHeader.call(context,context,[])
  assert.match(words(header),/剧情时间 · 离开后暂停/)
  assert.doesNotMatch(words(header),/现实陪伴 · 使用当地时间|当前篇章已暂停主动搭话/)
  assert.equal(f.events.filter(e=>e.method==='POST').length,1)
  assert.equal(f.events.filter(e=>e==='send').length,['rewrite','retry'].includes(action)?1:0)
  if(action==='edit')assert.equal(f.c.text.value,'修改的问题')
  assert.equal(f.c.historyBusy.value,false)
})

test('late rewind state cannot replace a newer chapter and cannot replay its draft',async t=>{
  const f=fixture(t),pending=deferred(),started=deferred()
  f.c.json=async(path,body,method)=>{if(method==='POST')return {};if(path==='/api/rp/a'){started.resolve();return pending.promise}return {state:{mode:'companion',location:'新章'}}}
  const editing=f.editMessage(f.c.messages.value[0]);await started.promise
  f.c.conversation.value='b';await f.loadConversationState()
  pending.resolve({state:f.restored});await editing
  assert.equal(f.c.conversationState.value.location,'新章');assert.equal(f.c.text.value,'')
})

test('later state request in the same chapter wins over rewind refresh',async t=>{
  const f=fixture(t),pending=deferred(),started=deferred();let gets=0
  f.c.json=async(path,body,method)=>{if(method==='POST')return {};if(++gets===1){started.resolve();return pending.promise}return {state:{mode:'companion',location:'更新场景'}}}
  const rewinding=f.applyRewind('a',11);await started.promise;await f.loadConversationState()
  pending.resolve({state:f.restored});assert.equal(await rewinding,true)
  assert.equal(f.c.conversationState.value.location,'更新场景')
})

test('navigation during rewind POST does not refresh another chapter or send',async t=>{
  const f=fixture(t),pending=deferred(),started=deferred()
  f.c.json=async()=>{started.resolve();return pending.promise}
  const rewriting=f.rewrite(1);await started.promise;f.c.conversation.value='b';pending.resolve({});await rewriting
  assert.deepEqual(f.events,[]);assert.equal(f.c.historyBusy.value,false)
})

test('failed refresh never retries a successful rewind or sends replay',async t=>{
  const f=fixture(t);let posts=0
  f.c.json=async(path,body,method)=>{if(method==='POST'){posts++;return {}}throw new Error('refresh failed')}
  await f.rewrite(1)
  assert.equal(posts,1);assert.deepEqual(f.errors,['refresh failed']);assert.ok(!f.events.includes('send'));assert.equal(f.c.historyBusy.value,false)
})

test('cancel or rejected rewind keeps cached mode and releases history lock',async t=>{
  const f=fixture(t);f.c.askAction=async()=>null
  await f.rewind(f.c.messages.value[1]);assert.deepEqual(f.events,[])
  f.c.json=async()=>{throw new Error('rewind failed')}
  assert.equal(await f.applyRewind('a',11),undefined)
  assert.equal(f.c.conversationState.value.mode,'companion');assert.equal(f.c.historyBusy.value,false)
})

test('compiled character options merge duplicate idle and preserve every remaining original motion index',async()=>{
  const source=fs.readFileSync(new URL('../src/release/CharacterStage.vue',import.meta.url),'utf8')
  const optionsCode=source.slice(source.indexOf('const selectableMotions='),source.indexOf('\nconst props='))
  const playCode=source.slice(source.indexOf('function playMotion()'),source.indexOf('\nfunction playExpression()'))
  const calls=[],c={allLive2dMotions,model:{},motion:Vue.ref(''),live2dController:{triggerMotion:(item,manual,priority)=>{calls.push({item,manual,priority});return true}},performAction:async(label,fn)=>fn()}
  const {selectableMotions,playMotion}=vm.runInNewContext(`${optionsCode}\n${playCode}\n;({selectableMotions,playMotion})`,c)
  const render=new Function('Vue',compile(parse(source).descriptor.template.content,{mode:'function'}).code)(Vue)
  const ctx={enabled:true,ready:true,error:'',motion:'',expression:'',actionBusy:false,actionStatus:'',selectableMotions,allLive2dExpressions,playMotion,playExpression:()=>{},interact:()=>{},$emit:()=>{},canvas:null}
  const vnode=render.call(ctx,ctx,[]),selects=[];walk(vnode,v=>{if(v.type==='select')selects.push(v)})
  const options=[];walk(selects[0],v=>{if(v.type==='option'&&v.props?.value!=='')options.push(v)})
  assert.equal(options.length,10);assert.equal(options.filter(v=>words(v)==='待机').length,1)
  assert.ok(!options.some(v=>words(v).includes('掐腰')))
  for(const option of options){
    c.motion.value=option.props.value;await playMotion()
    const call=calls.at(-1),original=allLive2dMotions[Number(option.props.value)]
    assert.equal(words(option),original.name);assert.equal(call.item,original);assert.equal(call.manual,true);assert.equal(call.priority,3)
  }
  assert.deepEqual(calls.map(({item})=>`${item.group}:${item.index}`),['Idle:0','Entry:0','Tap#4:0','Tap#4:1','Tap#4:2','Tap#4:3','Tap#4:5','Tap#4:6','Tap#4:7','Tap#4:8'])
  assert.equal(allLive2dMotions[6].index,4) // Internal alias remains available to compatibility callers.
})
