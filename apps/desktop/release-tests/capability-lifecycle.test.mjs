import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {ref} from 'vue'
const source=readFileSync(new URL('../src/release/CapabilityPanel.vue',import.meta.url),'utf8')
const body=source.slice(source.indexOf('let epoch=0'),source.indexOf('function newPrime'))
test('reopening ignores old response and failure while retaining the current request lock',async()=>{
 const pending=[]
 const json=()=>new Promise((resolve,reject)=>pending.push({resolve,reject}))
 const f=new Function('ref','json',`const busy=ref(false),error=ref(''),notice=ref(''),servers=ref([]),skills=ref([]),serverForm=ref(null),skillForm=ref(null),tools=ref([]),toolServer=ref(null),selectedTool=ref(null),toolResult=ref(''),argumentsText=ref('{}'),primeNumber=ref('97');${body};return {run,resetPanel,tools,busy,error}`)(ref,json)
 const old=f.run(async request=>{f.tools.value=await request('old')});f.resetPanel()
 const current=f.run(async request=>{f.tools.value=await request('new')})
 pending[0].resolve(['stale']);await old;assert.deepEqual(f.tools.value,[]);assert.equal(f.busy.value,true)
 pending[1].resolve(['fresh']);await current;assert.deepEqual(f.tools.value,['fresh'])
 const failed=f.run(async request=>{await request('failure')});f.resetPanel();pending[2].reject(new Error('old failure'));await failed
 assert.equal(f.error.value,'');assert.deepEqual(f.tools.value,[])
})
test('selection controls stay locked during execution and deleted forms are cleared',()=>{
 assert.match(source,/:disabled="busy" @click="selectTool\(t\)"/)
 assert.match(source,/:disabled="busy" @click="editServer\(s\)"/)
 assert.match(source,/if\(serverForm.value\?\.id===s.id\)serverForm.value=null/)
 assert.match(source,/if\(editingSkillName.value===s.name\)skillForm.value=null/)
})
test('unsupported tool model has an actionable Chinese explanation',async()=>{
 const {failureDescription}=await import('../src/release/failureCategory.mjs')
 assert.match(failureDescription('model_tools_unsupported'),/模型不支持工具调用.*关闭相关 MCP/)
})
