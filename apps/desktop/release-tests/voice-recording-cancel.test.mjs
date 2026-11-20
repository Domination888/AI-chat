import { releaseAppSource } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import {ref,watch,effectScope,nextTick} from 'vue'

const recorderSource=fs.readFileSync(new URL('../src/composables/useVoiceRecorder.js',import.meta.url),'utf8')
const recorderCode=recorderSource.slice(recorderSource.indexOf('export function useVoiceRecorder')).replace(/^export /,'')
const appSource=releaseAppSource()
const navigationCode=appSource.slice(appSource.indexOf("watch(page,(value,previous)=>"),appSource.indexOf('\nasync function promptModelSetup()'))
function fixture(t){
  const uploads=[],recorders=[]
  let stopped=0
  const scope=effectScope();t.after(()=>scope.stop())
  const stream={getTracks:()=>[{stop:()=>{stopped++}}]}
  const context={ref,Blob,onBeforeUnmount:()=>{},navigator:{mediaDevices:{getUserMedia:async()=>stream}},MediaRecorder:class{
    constructor(){this.state='inactive';this.mimeType='audio/webm';recorders.push(this)}
    start(){this.state='recording'}
    stop(){this.state='inactive';this.ondataavailable?.({data:new Blob(['test audio'])});this.onstop?.()}
  }}
  const useRecorder=vm.runInNewContext(`${recorderCode}\nuseVoiceRecorder`,context)
  const recorder=useRecorder({beforeStart:async()=>{},onAudio:blob=>uploads.push(blob),onError:error=>assert.fail(error.message)})
  const page=ref('story')
  scope.run(()=>vm.runInNewContext(navigationCode,{watch,page,cancelRecording:recorder.cancelRecording}))
  return {context,uploads,recorders,recorder,page,stream,stopped:()=>stopped}
}

test('cancel recording releases the microphone and never sends the recorded audio',async t=>{
  const f=fixture(t)
  await f.recorder.startRecording()
  f.recorder.cancelRecording();await nextTick()
  assert.equal(f.recorder.isRecording.value,false)
  assert.equal(f.recorders[0].state,'inactive')
  assert.ok(f.stopped()>0)
  assert.equal(f.uploads.length,0)
})

test('navigating from a story to the library or guide cancels capture without upload',async t=>{
  for(const destination of ['library','guide'])await t.test(destination,async t=>{
    const f=fixture(t)
    await f.recorder.startRecording()
    f.page.value=destination
    await nextTick()
    assert.equal(f.recorder.isRecording.value,false)
    assert.ok(f.stopped()>0)
    assert.equal(f.uploads.length,0)
  })
})

test('a late microphone acquisition is released after navigation without creating a recorder',async t=>{
  const f=fixture(t)
  let acquire,started
  const requested=new Promise(resolve=>{started=resolve})
  f.context.navigator.mediaDevices.getUserMedia=()=>{started();return new Promise(resolve=>{acquire=resolve})}
  const starting=f.recorder.startRecording()
  await requested
  f.page.value='guide'
  acquire(f.stream);await starting
  assert.equal(f.recorder.isRecording.value,false)
  assert.equal(f.recorders.length,0)
  assert.ok(f.stopped()>0)
  assert.equal(f.uploads.length,0)
})

test('ending recording normally still sends the captured audio once',async t=>{
  const f=fixture(t)
  await f.recorder.startRecording()
  f.recorder.stopRecording();await nextTick()
  assert.equal(f.recorder.isRecording.value,false)
  assert.equal(f.uploads.length,1)
  assert.ok(f.stopped()>0)
})
