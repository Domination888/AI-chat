import {test} from 'node:test'
import assert from 'node:assert/strict'
test('expired desktop session keeps the configured service available for login',async t=>{
 const previous=globalThis.window;t.after(()=>{if(previous===undefined)delete globalThis.window;else globalThis.window=previous})
 globalThis.window={storyDesktop:{bootstrap:async()=>({mode:'cloud',apiBase:'https://story.test',hasSession:true}),refreshSession:async()=>{throw new Error('expired')}}}
 const api=await import('../src/release/api.mjs?expired-session-test');assert.equal(await api.initialize(),null);assert.equal(api.apiBase(),'https://story.test');assert.equal(api.runtimeInfo.sessionExpired,true)
})
test('browser reload remembers the service origin without persisting credentials',async t=>{
 const previous=globalThis.window;t.after(()=>{if(previous===undefined)delete globalThis.window;else globalThis.window=previous})
 const storage=new Map()
 globalThis.window={localStorage:{setItem:(key,value)=>storage.set(key,value),getItem:key=>storage.get(key)}}
 const before=await import('../src/release/api.mjs?browser-before-reload')
 await before.connect('https://story.test/')
 await before.acceptSession({accessToken:'private-access',refreshToken:'private-refresh',user:{id:1}})
 assert.deepEqual([...storage.values()],['https://story.test'])
 const after=await import('../src/release/api.mjs?browser-after-reload')
 assert.equal(await after.initialize(),null)
 assert.equal(after.apiBase(),'https://story.test')
})
test('revoked refresh emits one session-expired transition and rejects the protected request',async t=>{
 const previousWindow=globalThis.window,previousFetch=globalThis.fetch
 t.after(()=>{globalThis.window=previousWindow;globalThis.fetch=previousFetch})
 globalThis.window={};let refreshes=0,protectedCalls=0,notifications=0
 globalThis.fetch=async address=>{
  if(address.endsWith('/api/v1/auth/refresh')){refreshes++;return new Response(JSON.stringify({message:'请重新登录'}),{status:401})}
  protectedCalls++;return new Response('{}',{status:401})
 }
 const api=await import('../src/release/api.mjs?revoked-session-test');await api.acceptSession({accessToken:'old',refreshToken:'revoked',user:{id:1}})
 const unsubscribe=api.onSessionExpired(()=>notifications++)
 await assert.rejects(api.request('/api/chat',{method:'POST',body:'draft'}),api.isSessionExpired)
 await assert.rejects(api.request('/api/v1/me'),api.isSessionExpired)
 assert.equal(refreshes,1);assert.equal(protectedCalls,2);assert.equal(notifications,1);unsubscribe()
})
test('successful silent refresh retries the request without asking the user to log in',async t=>{
 const previousWindow=globalThis.window,previousFetch=globalThis.fetch
 t.after(()=>{globalThis.window=previousWindow;globalThis.fetch=previousFetch})
 globalThis.window={};const calls=[];let notices=0
 globalThis.fetch=async(address,init)=>{calls.push([address,init]);if(address.endsWith('/api/v1/auth/refresh'))return new Response(JSON.stringify({accessToken:'new',refreshToken:'rotated',user:{id:1}}));return new Response('{}',{status:init.headers.get('Authorization')==='Bearer new'?200:401})}
 const api=await import('../src/release/api.mjs?silent-refresh-test');await api.acceptSession({accessToken:'old',refreshToken:'refresh',user:{id:1}});api.onSessionExpired(()=>notices++)
 assert.equal((await api.request('/api/chat',{method:'POST',body:'same draft'})).status,200)
 assert.equal(calls.length,3);assert.equal(calls[2][1].body,'same draft');assert.equal(notices,0)
})
test('a retry rejected after refresh expires the session but wrong login credentials do not',async t=>{
 const previousWindow=globalThis.window,previousFetch=globalThis.fetch
 t.after(()=>{globalThis.window=previousWindow;globalThis.fetch=previousFetch})
 globalThis.window={};let notices=0
 globalThis.fetch=async address=>new Response(JSON.stringify(address.endsWith('/api/v1/auth/refresh')?{accessToken:'also-revoked',refreshToken:'rotated',user:{id:1}}:{message:'密码不正确'}),{status:address.endsWith('/api/v1/auth/refresh')?200:401})
 const api=await import('../src/release/api.mjs?second-401-test');api.onSessionExpired(()=>notices++)
 await assert.rejects(api.json('/api/v1/auth/login',{},'POST'),/密码不正确/);assert.equal(notices,0)
 await api.acceptSession({accessToken:'old',refreshToken:'refresh',user:{id:1}})
 await assert.rejects(api.json('/api/v1/me'),api.isSessionExpired);assert.equal(notices,1)
})
test('desktop refresh rejection requests login without changing secure session storage APIs',async t=>{
 const previousWindow=globalThis.window,previousFetch=globalThis.fetch
 t.after(()=>{globalThis.window=previousWindow;globalThis.fetch=previousFetch})
 const calls=[];globalThis.window={storyDesktop:{saveSession:async()=>calls.push('save'),refreshSession:async()=>{calls.push('refresh');throw new Error('revoked')}}};globalThis.fetch=async()=>new Response('{}',{status:401})
 const api=await import('../src/release/api.mjs?desktop-revocation-test');await api.acceptSession({accessToken:'old',refreshToken:'secure-refresh',user:{id:1}});calls.length=0
 await assert.rejects(api.request('/api/chat'),api.isSessionExpired);assert.deepEqual(calls,['refresh'])
})
