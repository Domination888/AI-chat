import { releaseAppSource } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'

const source=releaseAppSource()
const code=source.slice(source.indexOf('async function switchConversationMode(mode){'),source.indexOf('\nasync function openGuide()'))
function fixture(){
  const calls=[],state={mode:'story',location:'雨天的书店',relationship:'旧友',commitments:['周末再见'],paused:true}
  const context={modeBusy:{value:false},conversation:{value:'chapter'},guarded:fn=>fn(),stop:async()=>calls.push('stop'),json:async(path,body,method)=>{calls.push({path,body,method});return {state}},loadConversationState:async()=>calls.push('refresh'),toast:message=>calls.push(message)}
  return {calls,state,context,change:vm.runInNewContext(`${code}\nswitchConversationMode`,context)}
}

test('switching to real-world companionship preserves scene, promises and the user pause choice',async()=>{
  const {calls,state,context,change}=fixture()
  await change('companion')
  assert.equal(calls[0],'stop')
  const update=calls.find(call=>call.method==='PUT')
  assert.equal(update.path,'/api/rp/chapter')
  assert.equal(JSON.stringify(update.body),JSON.stringify({...state,mode:'companion'}))
  assert.ok(calls.includes('refresh'))
  assert.equal(context.modeBusy.value,false)
})

test('a chapter change while reading the scene prevents a stale mode update',async()=>{
  const {calls,state,context,change}=fixture()
  context.json=async(path,body,method)=>{calls.push({path,body,method});context.conversation.value='another-chapter';return {state}}
  await change('companion')
  assert.ok(!calls.some(call=>call.method==='PUT'))
  assert.ok(!calls.includes('refresh'))
  assert.equal(context.modeBusy.value,false)
})
