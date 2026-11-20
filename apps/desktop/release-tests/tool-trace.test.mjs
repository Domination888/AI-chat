import { releaseAppSource } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import {ref,watch,effectScope} from 'vue'
import {applyToolStatus,toolPhaseLabel} from '../src/release/toolTrace.mjs'

const source=releaseAppSource()
const mapCode=source.slice(source.indexOf('function mapMessage(row){'),source.indexOf('\nasync function loadMessages()'))
const mapMessage=vm.runInNewContext(`${mapCode}\nmapMessage`,{imageUrls:encoded=>JSON.parse(encoded||'[]')})

test('structured start and success update one reactive card and survive the actual history mapper',t=>{
  const scope=effectScope();t.after(()=>scope.stop())
  const message=ref({role:'ai',content:''}),phases=[]
  scope.run(()=>watch(message,()=>{const phase=message.value.toolCalls?.[0]?.phase;if(phase)phases.push(phase)},{deep:true,flush:'sync'}))
  const running={id:'actual-call',name:'isPrime',serverName:'内置质数工具',phase:'running',arguments:{number:97}}
  applyToolStatus(message.value,running)
  applyToolStatus(message.value,{...running,phase:'succeeded',result:'97 是质数'})
  applyToolStatus(message.value,{kind:'plan',phase:'completed',available:1})
  assert.equal(message.value.toolCalls.length,1)
  assert.ok(phases.includes('running'));assert.ok(phases.includes('succeeded'))
  const saved=mapMessage({id:11,sender:'assistant',content:'97 是质数',context_json:JSON.stringify({toolCalls:message.value.toolCalls,toolPlanning:message.value.toolPlanning}),status:'complete'})
  assert.equal(saved.toolCalls.length,1)
  assert.equal(saved.toolCalls[0].result,'97 是质数')
  assert.equal(saved.toolCalls[0].phase,'succeeded')
  assert.equal(toolPhaseLabel(saved.toolCalls[0].phase),'执行成功')
})

test('a failed real execution remains a failed card after a failed reply reload',()=>{
  const message={role:'ai',content:''}
  applyToolStatus(message,{id:'failed-call',name:'echo',phase:'running',arguments:{text:'test'}})
  applyToolStatus(message,{id:'failed-call',name:'echo',phase:'failed',arguments:{text:'test'},error:'工具执行失败，请核对参数。'})
  const saved=mapMessage({id:12,sender:'assistant',content:'',context_json:JSON.stringify({toolCalls:message.toolCalls}),status:'failed'})
  assert.equal(saved.toolCalls[0].phase,'failed')
  assert.equal(saved.toolCalls[0].result,undefined)
  assert.equal(toolPhaseLabel(saved.toolCalls[0].phase),'执行失败')
})

test('assistant claims, rejected planning and incomplete events never fabricate execution cards',()=>{
  const message={role:'ai',content:'我已经调用工具验证了所有数字。'}
  applyToolStatus(message,{name:'isPrime',phase:'succeeded',result:'没有执行ID的文字'})
  assert.equal(message.toolCalls,undefined)
  applyToolStatus(message,{kind:'plan',phase:'failed',available:1,message:'尚未执行这批调用，请拆分任务。'})
  assert.equal(message.toolCalls,undefined)
  const saved=mapMessage({id:13,sender:'assistant',content:message.content,context_json:JSON.stringify({toolPlanning:message.toolPlanning}),status:'failed'})
  assert.equal(saved.toolCalls,undefined)
  assert.equal(saved.toolPlanning.phase,'failed')
  assert.match(saved.toolPlanning.message,/尚未执行/)
})
