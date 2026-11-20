import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import {parseConfigJson,parseCommand,parseHeaders,parseAdvanced,advancedExample} from '../src/release/capabilityConfig.mjs'
test('JSON errors identify the field and required object or array',()=>{
 assert.throws(()=>parseConfigJson('{bad','工具参数'),/工具参数.*有效.*JSON 对象/)
 for(const text of ['null','[]','42'])assert.throws(()=>parseConfigJson(text,'工具参数'),/工具参数.*JSON 对象/)
 assert.throws(()=>parseCommand('{}'),/启动命令.*JSON 数组/)
 assert.throws(()=>parseCommand('[]'),/非空字符串数组/)
 assert.throws(()=>parseCommand('["python3",42]'),/字符串数组/)
 assert.deepEqual(parseCommand('["python3","tool.py"]'),['python3','tool.py'])
 assert.throws(()=>parseHeaders('{"Authorization":42}'),/请求头.*字符串/)
})
test('advanced example is valid and never enables a schedule or grants tool authorization',()=>{
 const value=parseAdvanced(advancedExample);assert.equal(value.schedule.enabled,false);assert.equal(value.proactive.enabled,false);assert.deepEqual(value.mcpTools,[])
 assert.throws(()=>parseAdvanced('{"schedule":[]}'),/schedule.*JSON 对象/)
 assert.throws(()=>parseAdvanced('{"mcpTools":"echo"}'),/工具名字符串数组/)
 assert.throws(()=>parseAdvanced('{"name":"accidental-override"}'),/对应字段/)
 assert.deepEqual(parseAdvanced('{}'),{})
})
test('invalid JSON remains in the editor and does not send a tool request',async()=>{
 const source=fs.readFileSync(new URL('../src/release/CapabilityPanel.vue',import.meta.url),'utf8'),start=source.indexOf('async function runTool()'),end=source.indexOf('\nfunction editSkill',start)
 let message='',sent=false
 const context={run:async fn=>{try{await fn()}catch(e){message=e.message}},parseConfigJson,toolResult:{value:'old'},toolServer:{value:{id:'fixture'}},selectedTool:{value:{name:'echo'}},argumentsText:{value:'{bad'},json:async()=>{sent=true}}
 await vm.runInNewContext(source.slice(start,end)+'\nrunTool',context)()
 assert.equal(sent,false);assert.equal(context.argumentsText.value,'{bad');assert.match(message,/工具参数.*JSON 对象/)
})
