import { releaseAppSource } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import {ref,nextTick,effectScope} from 'vue'
import {regenerationAction} from '../src/release/messageActions.mjs'
import {useSendDraftRecovery} from '../src/release/sendDraftRecovery.mjs'

const source=releaseAppSource()
const extract=(start,end)=>source.slice(source.indexOf(start),source.indexOf(end,source.indexOf(start)))
const code=extract('async function rewrite(index,continuePaused=false){','\nasync function bookmark(')+extract('async function continueCurrentStory(){','\nasync function openChapter(')
function fixture(t){
  const scope=effectScope(),calls=[],notices=[]
  t.after(()=>scope.stop())
  const c={pendingContinuation:ref(null),pendingDailyIssue:ref(null),historyBusy:ref(false),busy:ref(false),conversation:ref('chapter-a'),text:ref('尚未发送的草稿'),images:ref(['data:image/png;base64,photo']),messages:ref([{role:'user',content:'写一个故事'},{id:72,role:'ai',content:'风吹过窗边，',status:'interrupted',completion:{reason:'length',incomplete:true}}]),regenerationAction,nextTick,document:{querySelector:()=>({focus(){}})},toast:m=>notices.push(m),guarded:fn=>fn(),json:async path=>{calls.push(['get',path]);return {worldId:'world-a'}},newStory:async world=>calls.push(['newStory',world]),askAction:async()=>{throw Error('continuation must not request a rewind')},applyRewind:async()=>{throw Error('continuation must preserve history')},send:async()=>{throw Error('continuation must await user review')}}
  let methods
  scope.run(()=>{
    c.sendDraftRecovery=useSendDraftRecovery({scope:()=>({userId:1,base:'test',conversationId:c.conversation.value,inStory:true}),readDraft:()=>({text:c.text.value,images:c.images.value}),writeDraft:d=>{c.text.value=d.text;c.images.value=d.images}})
    methods=vm.runInNewContext(`${code}\n;({rewrite,continueCurrentStory})`,c)
  })
  return {c,calls,notices,...methods}
}
test('continuation keeps truncated history and an existing text/photo draft without sending',async t=>{
  const f=fixture(t),original=JSON.stringify(f.c.messages.value)
  await f.rewrite(1)
  assert.match(f.c.text.value,/从上一条回复中断/)
  assert.equal(f.c.images.value.length,0)
  assert.equal(JSON.stringify(f.c.messages.value),original)
  assert.equal(f.c.sendDraftRecovery.visible.value.length,1)
  const saved=f.c.sendDraftRecovery.visible.value[0]
  assert.equal(saved.text,'尚未发送的草稿')
  assert.equal(saved.images[0],'data:image/png;base64,photo')
  await f.rewrite(1)
  assert.equal(f.c.sendDraftRecovery.visible.value.length,1)
  f.c.sendDraftRecovery.restore(saved.id)
  assert.equal(f.c.text.value,'尚未发送的草稿')
  assert.equal(f.c.images.value[0],'data:image/png;base64,photo')
  assert.equal(f.calls.length,0)
})
test('continuing an older reply identifies the selected text instead of a later reply',async t=>{
  const f=fixture(t)
  f.c.messages.value.push({role:'user',content:'别的话题'},{role:'ai',content:'另一条回复'})
  await f.rewrite(1)
  assert.equal(f.c.pendingContinuation.value.id,72)
  assert.match(f.c.text.value,/风吹过窗边/)
  assert.doesNotMatch(f.c.text.value,/另一条回复/)
})
test('continue chapter uses its current world while independent creation stays separate',async t=>{
  const f=fixture(t)
  await f.continueCurrentStory()
  assert.deepEqual(f.calls,[['get','/api/rp/chapter-a'],['newStory','world-a']])
})
test('navigation during world lookup cannot continue the newly selected chapter',async t=>{
  const f=fixture(t)
  let resolve
  f.c.json=()=>new Promise(r=>{resolve=r})
  const pending=f.continueCurrentStory()
  f.c.conversation.value='chapter-b'
  resolve({worldId:'world-a'})
  await pending
  assert.equal(f.calls.length,0)
})
test('failed continuation draft retains target through save and restore',async()=>{
 const {useSendDraftRecovery}=await import('../src/release/sendDraftRecovery.mjs')
 const {ref}=await import('vue');let draft={text:'',images:[]};const scope=()=>({userId:1,base:'https://fixture.test',conversationId:'c',inStory:true})
 const store=useSendDraftRecovery({scope,readDraft:()=>draft,writeDraft:v=>{draft=v}})
 const item=store.capture('接着完成',[],null,72);store.recover(item)
 assert.equal(draft.continuationHistoryId,72)
 draft={text:'另一草稿',images:[],continuationHistoryId:90};store.recover(item,{inline:false});store.restore(item.id)
 assert.equal(draft.continuationHistoryId,72);store.restore(item.id);assert.equal(draft.continuationHistoryId,90)
})
test('paused visible replies offer continuation alongside retry, without duplicate length action',async()=>{
 const {manualContinuationAvailable,regenerationAction}=await import('../src/release/messageActions.mjs')
 const reply={id:72,role:'ai',status:'interrupted',content:'已写到这里'}
 assert.equal(manualContinuationAvailable(reply),true)
 assert.equal(regenerationAction([{role:'user',content:'原任务'},reply],1).label,'重试')
 for(const extra of [{status:'failed'},{status:'complete'},{content:''},{completion:{incomplete:true,reason:'length'}}])assert.equal(manualContinuationAvailable({...reply,...extra}),false)
})
test('manual pause continuation keeps original reply identity and never starts retry rewind',async t=>{
 const f=fixture(t);f.c.messages.value[1].completion=null
 f.c.manualContinuationAvailable=reply=>reply.status==='interrupted'&&!!reply.content
 await f.rewrite(1,true)
 assert.equal(f.c.pendingContinuation.value.id,72);assert.equal(f.calls.length,0)
})
test('actual compiled message template emits numeric targets into the real rewrite handler',async t=>{
 const Vue=await import('vue');const {compile}=await import('@vue/compiler-dom');const {manualContinuationAvailable}=await import('../src/release/messageActions.mjs')
 const s=fs.readFileSync(new URL('../src/components/chat/ChatMessageList.vue',import.meta.url),'utf8');const {parse}=await import('@vue/compiler-sfc');const template=parse(s).descriptor.template.content
 const render=new Function('Vue',compile(template,{mode:'function'}).code)(Vue)
 for(const status of ['complete','failed','length','manual']){
  const f=fixture(t);const reply={id:72,role:'ai',content:status==='failed'?'':'partial',status:status==='complete'?'complete':status==='failed'?'failed':'interrupted',...(status==='length'?{completion:{reason:'length',incomplete:true}}:{})}
  f.c.messages.value=[{id:3,role:'ai',content:'首条开场'}, {id:70,role:'user',content:'真实问题',images:['photo']},reply];f.c.manualContinuationAvailable=manualContinuationAvailable
  const emitted=[];const ctx={messages:f.c.messages.value,selectedRole:{name:'角色'},searchQuery:'',hasOlder:true,loading:false,externalLinks:true,regenerationAction,manualContinuationAvailable,toolPhaseLabel:()=>'',container:null,$emit:(...args)=>emitted.push(args)}
  const vnode=render.call(ctx,ctx,[]);const buttons=[];function walk(v){if(!v||typeof v!=='object')return;if(v.type==='button')buttons.push(v);if(Array.isArray(v.children))v.children.forEach(walk)}walk(vnode)
  const name=status==='length'?'继续回复':status==='complete'?'重写':'重试';const label=v=>Array.isArray(v.children)?v.children.map(x=>x.children||'').join(''):v.children
  const button=buttons.find(v=>String(label(v)).trim()===name);assert.ok(button,status);button.props.onClick();assert.deepEqual(emitted.pop(),['regenerate',2])
  if(status==='length'){await f.rewrite(2);assert.equal(f.c.pendingContinuation.value.id,72);assert.equal(f.c.sendDraftRecovery.visible.value[0].images[0],'data:image/png;base64,photo')}
  else {f.c.rewindScope='边界';f.c.dailyIssueForReplay=()=> '2026-10-30';f.c.askAction=async()=>false;await f.rewrite(2);assert.equal(f.notices.some(x=>x.includes('开场白')),false)}
  assert.equal(buttons.filter(v=>String(label(v)).trim()==='继续回复').length,status==='length'||status==='manual'?1:0)
 }
})
