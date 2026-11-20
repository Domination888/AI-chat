import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import {createActionRequest} from '../src/release/actionRequest.mjs'

const source=fs.readFileSync(new URL('../src/release/AdminApp.vue',import.meta.url),'utf8')
const code=source.slice(source.indexOf('async function toggleStatus(row){'),source.indexOf('</script>'))
const ref=value=>({value})
function fixture(){
  const calls=[]
  let visible=null,unmount
  const actions=createActionRequest(value=>{visible=value})
  const context={admin:ref({id:1}),busy:ref(false),disposed:false,sessionVersion:0,rows:ref([{id:7}]),invitation:ref({code:'test-only'}),askAction:actions.ask,settleAction:actions.settle,guard:async fn=>fn(),json:async(path,body,method)=>calls.push({path,body,method}),load:async()=>calls.push('load'),logout:async()=>calls.push('logout'),onBeforeUnmount:fn=>{unmount=fn}}
  const controller=vm.runInNewContext(`${code}\n;({toggleStatus,signOut})`,context)
  return {context,calls,actions,dialog:()=>visible,unmount:()=>unmount(),...controller}
}
const user=()=>({id:7,email:'traveller@test.example',status:'active',admin:false})

test('cancelling an account pause keeps the account unchanged and explains the selected target',async()=>{
  const f=fixture(),pending=f.toggleStatus(user())
  assert.match(f.dialog().preview,/traveller@test\.example/)
  assert.match(f.dialog().description,/无法登录/)
  assert.match(f.dialog().description,/回复会停止/)
  assert.equal(f.dialog().confirmLabel,'确认暂停')
  f.actions.settle(null);await pending
  assert.deepEqual(f.calls,[])
  assert.equal(f.dialog(),null)
})

test('one confirmation changes only the account and status shown when the dialog opened',async()=>{
  const f=fixture(),row=user(),pending=f.toggleStatus(row)
  // A refreshed table must not redirect an already displayed decision.
  row.id=99;row.status='suspended'
  f.actions.settle(true);f.actions.settle(true)
  await pending
  const writes=f.calls.filter(call=>call.method==='PUT')
  assert.equal(writes.length,1)
  assert.equal(writes[0].path,'/api/v1/admin/users/7/status')
  assert.equal(writes[0].body.status,'suspended')
  assert.equal(f.calls.filter(call=>call==='load').length,1)
})

test('restoring an account requires a separate confirmation describing a new login',async()=>{
  const f=fixture(),pending=f.toggleStatus({...user(),status:'suspended'})
  assert.equal(f.dialog().confirmLabel,'确认恢复')
  assert.match(f.dialog().description,/重新登录/)
  f.actions.settle(true);await pending
  const write=f.calls.find(call=>call.method==='PUT')
  assert.equal(write.path,'/api/v1/admin/users/7/status')
  assert.equal(write.body.status,'active')
})

test('signing out cancels an open decision and a late confirmation cannot submit it',async()=>{
  const f=fixture(),pending=f.toggleStatus(user())
  await f.signOut()
  f.actions.settle(true);await pending
  assert.equal(f.dialog(),null)
  assert.equal(f.context.admin.value,null)
  assert.deepEqual(f.calls,['logout'])
})

test('signing out before a queued confirmation resumes invalidates the old administrator session',async()=>{
  const f=fixture(),pending=f.toggleStatus(user())
  f.actions.settle(true)
  const exiting=f.signOut()
  // Even logging back in must not make the old decision current again.
  f.context.admin.value={id:1}
  await Promise.all([pending,exiting])
  assert.deepEqual(f.calls,['logout'])
})

test('unmount cancels an open decision before a late UI response',async()=>{
  const f=fixture(),pending=f.toggleStatus(user())
  f.unmount();f.actions.settle(true);await pending
  assert.equal(f.dialog(),null)
  assert.equal(f.context.disposed,true)
  assert.deepEqual(f.calls,[])
})
