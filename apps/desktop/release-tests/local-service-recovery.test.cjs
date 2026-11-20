const {test}=require('node:test')
const assert=require('node:assert/strict')
const {EventEmitter,once}=require('node:events')
const {spawn}=require('node:child_process')
const net=require('node:net')
const {LocalServiceSupervisor}=require('../electron/local-service-supervisor.cjs')
const pause=ms=>new Promise(r=>setTimeout(r,ms))
function child(){const c=new EventEmitter();c.exitCode=null;c.signalCode=null;return c}
function fixture(t,options={}){const s=new LocalServiceSupervisor({health:async()=>true,pollMs:100000,backoff:[1,1,1],canBind:async()=>true,...options});t.after(()=>s.stop());return s}
test('real short fake Node child dies after ready; only that owned child is replaced',async t=>{
 const s=fixture(t),events=[];s.onExit=e=>events.push(e)
 const c=spawn(process.execPath,['-e','setTimeout(()=>process.exit(7),100)'],{stdio:'ignore'})
 t.after(()=>{if(c.exitCode===null)c.kill()});let restarts=0;const replacement=child(),other=child()
 s.add('embedding',123,c,()=>{restarts++;return replacement});s.add('llm',124,other,()=>{throw Error('must not restart')})
 await s.tick();assert.equal(s.snapshot().embedding.state,'ready');await once(c,'exit');await pause(15)
 assert.equal(restarts,1);assert.equal(s.services.get('llm').child,other);assert.equal(events[0].code,7)
 await s.tick();assert.equal(s.snapshot().embedding.state,'ready');assert.equal(s.services.get('embedding').port,123)
})
test('crash loop bounded; stable health resets restart budget',async t=>{
 let time=0,n=0;const s=fixture(t,{now:()=>time,stableMs:50,backoff:[1,1]});let c=child()
 s.add('embedding',1,c,()=>{n++;return c=child()})
 c.emit('exit',null,'SIGTRAP');await pause(8);c.emit('exit',1,null);await pause(8);c.emit('exit',1,null);await pause(8)
 assert.equal(n,2);assert.equal(s.snapshot().embedding.state,'failed');assert.equal(s.snapshot().embedding.lastExit.code,1)
 await s.retry('embedding');assert.equal(n,3);await s.tick();time=60;await s.tick();assert.equal(s.snapshot().embedding.attempts,0)
})
test('old health results and stop cannot revive or overwrite a dead service',async t=>{
 let resolve;const s=fixture(t,{health:()=>new Promise(r=>resolve=r),backoff:[100]});const c=child();let spawns=0
 s.add('embedding',1,c,()=>{spawns++;return child()});const polling=s.tick();c.emit('exit',0,null);s.stop();resolve(true);await polling;await pause(110)
 assert.equal(spawns,0);assert.equal(s.snapshot().embedding.state,'stopped')
})
test('busy unrelated port stays alive; no sibling restart; no external service takeover',async t=>{
 const server=net.createServer();server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>new Promise(r=>server.close(r)))
 const s=new LocalServiceSupervisor({health:async()=>true,pollMs:100000,backoff:[1]});t.after(()=>s.stop());let spawns=0;const c=child()
 s.add('embedding',server.address().port,c,()=>{spawns++;return child()});c.emit('exit',1,null);await pause(15)
 assert.equal(spawns,0);assert.equal(s.snapshot().embedding.reason,'port_in_use');assert.equal(server.listening,true)
 assert.equal(await s.retry('external'),false);assert.deepEqual(Object.keys(s.snapshot()),['embedding'])
})
test('concurrent manual retry shares one spawn and stop during port check prevents spawn',async t=>{
 let resolve,n=0;const s=fixture(t,{canBind:()=>new Promise(r=>resolve=r),backoff:[]});const c=child()
 s.add('embedding',1,c,()=>{n++;return child()});c.emit('exit',1,null)
 const a=s.retry('embedding'),b=s.retry('embedding');resolve(true);await Promise.all([a,b]);assert.equal(n,1)
 s.services.get('embedding').child.emit('exit',1,null);const pending=s.retry('embedding');s.stop();resolve(true);await pending;assert.equal(n,1)
})
test('living unhealthy child is never killed or duplicated',async t=>{
 const s=fixture(t,{health:async()=>false});let n=0;const c=child();c.kill=()=>{throw Error('forbidden')}
 s.add('voice',1,c,()=>{n++;return child()});await s.tick();await s.tick();await s.tick()
 assert.equal(s.snapshot().voice.state,'unhealthy');assert.equal(await s.retry('voice'),false);assert.equal(n,0)
})
test('a late successful old-child health probe cannot mark its replacement ready',async t=>{
 let resolve;const s=fixture(t,{health:()=>new Promise(r=>resolve=r)}),old=child(),replacement=child()
 s.add('embedding',1,old,()=>replacement);const poll=s.tick();old.emit('exit',null,'SIGTRAP');await pause(8)
 assert.equal(s.services.get('embedding').child,replacement);assert.equal(s.snapshot().embedding.state,'starting')
 resolve(true);await poll;assert.equal(s.snapshot().embedding.state,'starting')
 assert.deepEqual(Object.keys(s.snapshot().embedding.lastExit).sort(),['code','signal','time'])
 assert.equal(s.snapshot().embedding.lastExit.signal,'SIGTRAP')
})
test('backend failure is observable but never automatically respawned',async t=>{
 const s=fixture(t),backend=child();s.add('backend',1,backend,null,false);backend.emit('exit',1,null);await pause(8)
 assert.equal(s.snapshot().backend.state,'failed');assert.equal(s.snapshot().backend.retryAvailable,false)
 assert.equal(await s.retry('backend'),false)
})
