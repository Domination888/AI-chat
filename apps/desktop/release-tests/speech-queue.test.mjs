import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createSpeechQueue} from '../src/release/speechQueue.mjs'

const tick=()=>new Promise(resolve=>setImmediate(resolve))
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
function fixture({request,resume,synthesisTimeoutMs}={}) {
  const calls=[],sources=[],errors=[]
  const audio={destination:{},resume:resume||(()=>Promise.resolve()),decodeAudioData:async()=>({}),close(){},createBufferSource(){const source={connect(){},start(){source.started=true},stop(){source.stopped=true;source.onended?.()}};sources.push(source);return source}}
  const queue=createSpeechQueue({speaking:{value:false},enabled:{value:true},roleId:()=>7,onError:error=>errors.push(error.message),createContext:()=>audio,lipSync:{start(){},stop(){}},synthesisTimeoutMs,request:async(path,options)=>{calls.push({path,options});return request?request(path,options):{ok:true,arrayBuffer:async()=>new ArrayBuffer(8)}}})
  return {queue,calls,sources,errors}
}
test('manual reading plays when automatic reading is off and marks pending synthesis busy',async()=>{
  const response=deferred(),f=fixture({request:()=>response.promise});f.queue.enabled.value=false
  f.queue.addText('自动朗读关闭，这段不应该播放。',true);assert.equal(f.calls.length,0)
  await f.queue.read('手动朗读仍然应该正常播放。');assert.equal(f.calls.length,1);assert.equal(f.queue.speaking.value,true);assert.equal(f.queue.phase.value,'preparing')
  response.resolve({ok:true,arrayBuffer:async()=>new ArrayBuffer(8)});await tick();assert.equal(f.sources[0].started,true);assert.equal(f.queue.phase.value,'playing')
  f.sources[0].onended();await tick();assert.equal(f.queue.speaking.value,false);assert.equal(f.queue.phase.value,'idle')
})
test('stop during pending audio unlock cannot restart cancelled manual reading',async()=>{
  const resumed=deferred(),f=fixture({resume:()=>resumed.promise})
  const reading=f.queue.read('这条手动朗读已经取消。');f.queue.stop();resumed.resolve();await reading;await tick()
  assert.equal(f.calls.length,0);assert.equal(f.sources.length,0)
})
test('stop aborts synthesis and a new manual reading waits for old cancellation then plays',async()=>{
  const old=deferred();let count=0;const f=fixture({request:async()=>++count===1?old.promise:{ok:true,arrayBuffer:async()=>new ArrayBuffer(8)}})
  await f.queue.read('第一条语音正在等待服务器。');const signal=f.calls[0].options.signal
  await f.queue.read('第二条语音应该播放。');assert.equal(signal.aborted,true)
  old.reject(new DOMException('Cancelled','AbortError'));await tick()
  assert.equal(f.calls.length,2);assert.equal(f.sources.length,1);assert.equal(f.sources[0].started,true);assert.deepEqual(f.errors,[])
  f.queue.close()
})
test('a failed response reports once per turn; later chunks are suppressed but next turn recovers',async()=>{
  let fail=true;const f=fixture({request:async()=>fail?{ok:false,json:async()=>({message:'朗读服务未就绪'})}:{ok:true,arrayBuffer:async()=>new ArrayBuffer(8)}})
  f.queue.addText('第一句台词触发服务器错误。',true);await tick();f.queue.addText('后续流式台词不能反复报错。',true);await tick()
  assert.equal(f.calls.length,1);assert.deepEqual(f.errors,['朗读服务未就绪']);assert.equal(f.queue.speaking.value,false)
  fail=false;f.queue.stop();f.queue.addText('下一轮恢复了语音服务。',true);await tick()
  assert.equal(f.calls.length,2);assert.equal(f.sources[0].started,true);f.queue.close()
})
test('manual narration-only text explains why there is no audio',async()=>{
  const f=fixture();await f.queue.read('（她轻轻点头。）');assert.equal(f.calls.length,0);assert.deepEqual(f.errors,['这段内容没有可朗读的台词'])
})
test('stopping a service that ignores cancellation releases the next reading immediately',async()=>{
  let count=0;const stalled=deferred(),f=fixture({request:()=>++count===1?stalled.promise:{ok:true,arrayBuffer:async()=>new ArrayBuffer(8)}})
  await f.queue.read('正在等待但不会响应取消的服务器。')
  await f.queue.read('下一次朗读不应等待旧服务返回。');await tick()
  assert.equal(f.calls.length,2);assert.equal(f.calls[0].options.signal.aborted,true)
  assert.equal(f.sources.length,1);assert.equal(f.sources[0].started,true);assert.deepEqual(f.errors,[])
  f.queue.close();stalled.resolve({ok:true,arrayBuffer:async()=>new ArrayBuffer(8)});await tick()
  assert.equal(f.sources.length,1)
})
test('a stalled response body times out once and a later reading can recover',async()=>{
  const body=deferred();let first=true
  const f=fixture({synthesisTimeoutMs:15,request:()=>first?{ok:true,arrayBuffer:()=>body.promise}:{ok:true,arrayBuffer:async()=>new ArrayBuffer(8)}})
  f.queue.addText('第一段已收到头但音频正文没有完成。',true)
  await new Promise(resolve=>setTimeout(resolve,35))
  assert.equal(f.calls[0].options.signal.aborted,true);assert.equal(f.queue.speaking.value,false)
  assert.equal(f.errors.length,1);assert.match(f.errors[0],/等待超时/)
  f.queue.addText('同一回合的后续语句不应反复失败。',true);await tick();assert.equal(f.calls.length,1)
  first=false;await f.queue.read('重新朗读现在可以正常开始。');await tick()
  assert.equal(f.sources[0].started,true)
  body.resolve(new ArrayBuffer(8));await tick();assert.equal(f.sources.length,1)
  f.queue.close()
})
