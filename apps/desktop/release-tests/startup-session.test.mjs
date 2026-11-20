import { releaseAppSource, runReleaseSnippet } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import {createRenderer,defineComponent,onMounted,ref,watch} from 'vue'

const source=releaseAppSource()
const code=source.slice(source.indexOf('onMounted(async()=>{try{'),source.indexOf('\nonBeforeUnmount(()=>{replyState.disposed=true'))
const renderer=createRenderer({createElement:()=>({children:[]}),createText:text=>({text}),createComment:()=>({}),insert:(node,parent)=>parent.children.push(node),remove:()=>{},setText:()=>{},setElementText:()=>{},parentNode:()=>null,nextSibling:()=>null,patchProp:()=>{}})
let moduleId=0
async function boot(t,{mode='cloud',desktop=true,hasSession=false,expired=false,base='https://story.test'}={}){
  const previousWindow=globalThis.window,previousFetch=globalThis.fetch,calls=[],user={id:7,username:'已恢复的创作者'}
  let refreshes=0,expiryEvents=0
  t.after(()=>{globalThis.window=previousWindow;globalThis.fetch=previousFetch})
  globalThis.window=desktop?{storyDesktop:{bootstrap:async()=>({mode,apiBase:base,hasSession,localToken:mode==='offline'?'local-offline-bearer':undefined}),refreshSession:async()=>{refreshes++;if(expired||!hasSession)throw new Error('saved session expired');return {user,accessToken:'restored-access',refreshToken:'rotated-refresh'}},saveSession:async()=>{}}}:{localStorage:{getItem:()=>base}}
  globalThis.fetch=async(address,init)=>{
    const path=new URL(address,'https://story.test').pathname
    calls.push({path,authorization:init.headers.get('Authorization')})
    if(path==='/api/health')return new Response(JSON.stringify({mode}))
    const authenticated=init.headers.get('Authorization')===(mode==='offline'?'Bearer local-offline-bearer':'Bearer restored-access')
    if(!authenticated)return new Response(JSON.stringify({message:'需要登录'}),{status:401})
    return new Response(JSON.stringify(path==='/api/v1/me'?{user}:path==='/api/roles'?[{id:1,name:'林间'}]:{}))
  }
  const api=await import(`../src/release/api.mjs?mounted-startup-${++moduleId}`)
  const unsubscribe=api.onSessionExpired(()=>{expiryEvents++});t.after(unsubscribe)
  const context={onMounted,initialize:api.initialize,json:api.json,apiBase:api.apiBase,offline:mode==='offline',desktop,window:globalThis.window,nativeInfo:ref({}),nativeTimer:ref(null),server:ref(''),connected:ref(false),me:ref(null),roles:ref([]),authNotice:ref(''),error:ref(''),booting:ref(true),setInterval:()=>1,toast:()=>{}}
  Object.defineProperty(context,'runtimeInfo',{get:()=>api.runtimeInfo})
  context.loadLibrary=async()=>{context.roles.value=await api.json('/api/roles')}
  context.initializeCompanion=async()=>{await api.json('/api/v1/me/companion')}
  let complete
  const done=new Promise(resolve=>{complete=resolve})
  const stop=watch(context.booting,value=>{if(!value)complete()},{flush:'sync'});t.after(stop)
  const app=renderer.createApp(defineComponent({setup(){runReleaseSnippet(code,context);return()=>null}}))
  app.mount({children:[]});t.after(()=>app.unmount())
  await done
  return {context,calls,user,refreshes:()=>refreshes,expiryEvents:()=>expiryEvents}
}

test('fresh commercial profile with no saved session opens normal login without me or refresh',async t=>{
  const f=await boot(t)
  assert.equal(f.context.me.value,null)
  assert.equal(f.context.connected.value,true)
  assert.equal(f.context.authNotice.value,'')
  assert.equal(f.refreshes(),0)
  assert.equal(f.expiryEvents(),0)
  assert.deepEqual(f.calls.map(call=>call.path),['/api/health'])
})

test('fresh profile without a service address performs no protected or public HTTP request',async t=>{
  const f=await boot(t,{base:''})
  assert.equal(f.context.connected.value,false)
  assert.equal(f.context.authNotice.value,'')
  assert.equal(f.refreshes(),0)
  assert.deepEqual(f.calls,[])
})

test('a valid saved desktop session restores its returned user before loading private data',async t=>{
  const f=await boot(t,{hasSession:true})
  assert.equal(f.refreshes(),1)
  assert.equal(f.context.me.value.id,f.user.id)
  assert.equal(f.context.roles.value[0].name,'林间')
  assert.equal(f.context.authNotice.value,'')
  assert.equal(f.expiryEvents(),0)
  assert.deepEqual(f.calls.map(call=>call.path),['/api/health','/api/roles','/api/v1/me/companion'])
  assert.ok(f.calls.slice(1).every(call=>call.authorization==='Bearer restored-access'))
})

test('a genuinely invalid saved session asks for login once without retrying an empty token',async t=>{
  const f=await boot(t,{hasSession:true,expired:true})
  assert.equal(f.refreshes(),1)
  assert.equal(f.context.me.value,null)
  assert.match(f.context.authNotice.value,/重新登录/)
  assert.deepEqual(f.calls.map(call=>call.path),['/api/health'])
})

test('browser refresh remembers the service but starts at normal login without old credentials',async t=>{
  const f=await boot(t,{desktop:false})
  assert.equal(f.context.server.value,'https://story.test')
  assert.equal(f.context.me.value,null)
  assert.equal(f.context.authNotice.value,'')
  assert.equal(f.expiryEvents(),0)
  assert.deepEqual(f.calls.map(call=>call.path),['/api/health'])
})

test('offline startup still reads the local identity with its bearer',async t=>{
  const f=await boot(t,{mode:'offline'})
  assert.equal(f.refreshes(),0)
  assert.equal(f.context.me.value.id,f.user.id)
  assert.equal(f.context.authNotice.value,'')
  assert.ok(f.calls.some(call=>call.path==='/api/v1/me'&&call.authorization==='Bearer local-offline-bearer'))
})
