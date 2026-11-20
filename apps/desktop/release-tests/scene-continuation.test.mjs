import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

const source=fs.readFileSync(new URL('../src/components/RpPanel.vue',import.meta.url),'utf8')
const code=source.slice(source.indexOf('async function saveScene('),source.indexOf('\nwatch('))
const ref=value=>({value})
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
function fixture(){
  const writes=[],emitted=[]
  const context={props:{show:true,conversationId:'chapter-a'},panelVersion:1,state:ref({mode:'story',location:'新填写的地点',relationship:'刚确认的朋友',commitments:['旧约定'],events:['旧经历']}),worldId:ref('world-a'),commitments:ref('待履行：周六一起整理书架\n\n完成：一起喝茶'),events:ref('窗边重逢\n一起关店'),busy:ref(false),error:ref(''),request:async(path,options)=>{writes.push({path,...options})},emit:(...args)=>emitted.push(args)}
  const actions=vm.runInNewContext(`${code}\n({save,saveAndContinue})`,context)
  return {context,writes,emitted,...actions}
}

test('continuing saves all current edits and waits for both requests before opening exactly one chapter',async()=>{
  const f=fixture(),put=deferred(),greeting=deferred()
  f.context.request=(path,options)=>{f.writes.push({path,...options});return options.method==='PUT'?put.promise:greeting.promise}
  const first=f.saveAndContinue()
  await f.saveAndContinue()
  assert.equal(f.writes.length,1)
  assert.equal(f.context.busy.value,true)
  assert.deepEqual(f.emitted,[])
  assert.deepEqual(JSON.parse(f.writes[0].body),{mode:'story',location:'新填写的地点',relationship:'刚确认的朋友',commitments:['待履行：周六一起整理书架','完成：一起喝茶'],events:['窗边重逢','一起关店']})
  put.resolve();await new Promise(resolve=>setImmediate(resolve))
  assert.equal(f.writes[1].path,'/api/rp/chapter-a/greeting')
  assert.deepEqual(f.emitted,[])
  greeting.resolve();await first
  assert.deepEqual(f.emitted,[['continue','world-a']])
  // The parent owns chapter creation after this event. Keep the button locked
  // until it changes context or closes the panel, so a double-click cannot fork twice.
  await f.saveAndContinue()
  assert.equal(f.writes.length,2)
})

test('ordinary save uses the same complete form snapshot and returns to the story',async()=>{
  const f=fixture()
  await f.save()
  assert.equal(f.writes[0].method,'PUT')
  assert.deepEqual(JSON.parse(f.writes[0].body).events,['窗边重逢','一起关店'])
  assert.deepEqual(f.emitted,[['saved'],['close']])
  assert.equal(f.context.busy.value,false)
})

test('a failed save or greeting keeps the edited form open and never starts a new chapter',async t=>{
  for(const failingMethod of ['PUT','POST'])await t.test(failingMethod,async()=>{
    const f=fixture()
    f.context.request=async(path,options)=>{f.writes.push({path,...options});if(options.method===failingMethod)throw new Error('保存未完成，请重试')}
    await f.saveAndContinue()
    assert.deepEqual(f.emitted,[])
    assert.equal(f.context.error.value,'保存未完成，请重试')
    assert.equal(f.context.commitments.value,'待履行：周六一起整理书架\n\n完成：一起喝茶')
    assert.equal(f.context.busy.value,false)
  })
})

test('a late save from another chapter cannot continue, close, or clear the new chapter state',async()=>{
  const f=fixture(),pending=deferred()
  f.context.request=(path,options)=>{f.writes.push({path,...options});return pending.promise}
  const saving=f.saveAndContinue()
  f.context.props.conversationId='chapter-b';f.context.panelVersion++
  f.context.busy.value=true;f.context.error.value='新篇章的提示'
  pending.resolve();await saving
  assert.equal(f.writes.length,1)
  assert.deepEqual(f.emitted,[])
  assert.equal(f.context.busy.value,true)
  assert.equal(f.context.error.value,'新篇章的提示')
})

test('closing and reopening the same chapter invalidates an older save failure',async()=>{
  const f=fixture(),pending=deferred()
  f.context.request=()=>pending.promise
  const saving=f.saveAndContinue()
  f.context.panelVersion++
  f.context.busy.value=true;f.context.error.value='重新打开后的提示'
  pending.reject(new Error('旧请求失败'));await saving
  assert.deepEqual(f.emitted,[])
  assert.equal(f.context.busy.value,true)
  assert.equal(f.context.error.value,'重新打开后的提示')
})

test('a late scene response cannot populate another chapter form',async()=>{
  const pending=deferred(),state=ref(null),error=ref(''),props={show:true,conversationId:'chapter-a'}
  const context={props,panelVersion:1,state,error,worldId:ref(''),commitments:ref(''),events:ref(''),request:()=>pending.promise,loadAlbum:async()=>assert.fail('must not load old album'),refreshDelivery:async()=>assert.fail('must not load old delivery')}
  const loadCode=source.slice(source.indexOf('async function load()'),source.indexOf('\nasync function refreshDelivery()'))
  const loading=vm.runInNewContext(`${loadCode}\nload`,context)()
  props.conversationId='chapter-b';context.panelVersion++
  pending.resolve({worldId:'world-a',state:{location:'旧地点',commitments:[],events:[]}})
  await loading
  assert.equal(state.value,null)
  assert.equal(error.value,'')
})
