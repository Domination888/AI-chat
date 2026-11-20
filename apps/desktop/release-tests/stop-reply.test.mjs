import { releaseAppSource, runReleaseSnippet } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import { regenerationAction } from '../src/release/messageActions.mjs'
const source=releaseAppSource()
const stopCode=source.slice(source.indexOf('async function stop(cancelCapture=true)'),source.indexOf('\nasync function send('))
const ref=value=>({value})
function fixture(){
  let resolve,reject
  const interrupted=new Promise((yes,no)=>{resolve=yes;reject=no})
  const calls=[]
  const context={generation:7,disposed:false,conversation:ref('a'),generating:ref(true),stopping:ref(false),proactiveIndex:1,controller:{abort(){calls.push('abort')}},stopSpeech(){calls.push('speech')},cancelRecording(){calls.push('capture')},json:async(url,body)=>{calls.push([url,body]);return interrupted},toast:message=>calls.push(['toast',message])}
  context.messages=[{role:'user',content:'讲个故事'},{role:'ai',content:''}]
  context.loadMessages=async()=>{calls.push('history');context.messages=[{id:11,role:'user',content:'讲个故事'},{id:12,role:'ai',content:'',status:'interrupted'}]}
  context.loadChapters=async()=>calls.push('chapters')
  const stop=runReleaseSnippet(`${stopCode}\nstop`,context)
  return {context,calls,stop,resolve,reject}
}
test('stop waits for persisted history then restores IDs and interrupted retry action',async()=>{
  const {context,calls,stop,resolve}=fixture();const done=stop()
  assert.equal(context.stopping.value,true);assert.equal(context.generating.value,true);assert.ok(!calls.includes('history'))
  resolve();await done
  assert.deepEqual(context.messages.map(m=>m.id),[11,12]);assert.equal(regenerationAction(context.messages,1).label,'重试')
  assert.equal(calls.filter(c=>c==='history').length,1);assert.equal(calls.filter(c=>c==='chapters').length,1)
  assert.equal(context.generating.value,false);assert.equal(context.stopping.value,false)
})
test('an interrupt error is visible and still attempts one history refresh',async()=>{
  const {context,calls,stop,reject}=fixture();const done=stop(false);reject(new Error('记录仍在保存'));await done
  assert.ok(calls.some(c=>Array.isArray(c)&&c[0]==='toast'&&c[1]==='记录仍在保存'))
  assert.equal(calls.filter(c=>c==='history').length,1);assert.ok(!calls.includes('capture'));assert.equal(context.generating.value,false)
})
test('late stop completion cannot replace another chapter or newer reply',async()=>{
  for(const change of [c=>c.conversation.value='b',c=>c.generation++,c=>c.disposed=true]){
    const {context,calls,stop,resolve}=fixture();const done=stop();change(context);resolve();await done
    assert.ok(!calls.includes('history'));assert.ok(!calls.includes('chapters'));assert.equal(context.generating.value,true)
  }
})
test('history requested before a newer generation cannot replace its placeholders',async()=>{
  const loadCode=source.slice(source.indexOf('async function loadMessages()'),source.indexOf('\nasync function older('))
  let resolve;const rows=new Promise(r=>resolve=r)
  const context={generation:1,selection:0,conversation:ref('a'),search:ref(''),messages:ref(['new reply']),hasOlder:ref(false),json:()=>rows,mapMessage:r=>r,bottom:async()=>{}}
  const load=runReleaseSnippet(`${loadCode}\nloadMessages`,context),pending=load();context.generation++;resolve([{id:1}]);await pending
  assert.deepEqual(context.messages.value,['new reply'])
})
