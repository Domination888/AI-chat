const net=require('net')
const available=port=>new Promise(resolve=>{const server=net.createServer();server.once('error',()=>resolve(false));server.listen(port,'127.0.0.1',()=>server.close(()=>resolve(true)))})
class LocalServiceSupervisor {
  constructor({health,onChange=()=>{},onExit=()=>{},canBind=available,pollMs=3000,backoff=[1000,3000,10000],stableMs=60000,startupMs=120000,now=Date.now}={}) {
    Object.assign(this,{health,onChange,onExit,canBind,pollMs,backoff,stableMs,startupMs,now});this.services=new Map();this.stopped=false;this.timer=null
  }
  add(kind,port,child,spawn,restartable=true) {
    const service={kind,port,child,spawn,restartable,state:'starting',attempts:0,failures:0,readySince:null,started:this.now(),lastExit:null,recovery:null,timer:null}
    this.services.set(kind,service);this.attach(service,child);this.publish();this.schedule()
  }
  attach(s,child) {
    const exited=(code,signal,reason)=>{
      if(this.stopped||s.child!==child||s.dead)return
      s.dead=true;s.state='failed';s.reason=reason;s.readySince=null
      s.lastExit={code:Number.isInteger(code)?code:null,signal:typeof signal==='string'&&/^SIG[A-Z0-9]+$/.test(signal)?signal:null,time:new Date(this.now()).toISOString()}
      this.onExit({kind:s.kind,...s.lastExit,reason});this.publish();this.queue(s)
    }
    s.dead=false
    child.once('exit',(code,signal)=>exited(code,signal,'process_exited'))
    child.once('error',()=>exited(null,null,'spawn_failed'))
    if(child.exitCode!=null||child.signalCode)exited(child.exitCode,child.signalCode,'process_exited')
  }
  snapshot() {
    return Object.fromEntries([...this.services].map(([kind,s])=>[kind,{state:s.state,attempts:s.attempts,lastExit:s.lastExit,reason:s.reason||'',retryAvailable:s.restartable&&!!s.dead&&!s.recovery&&!s.timer}]))
  }
  publish(){this.onChange(this.snapshot())}
  schedule(){if(!this.stopped&&!this.timer)this.timer=setTimeout(()=>{this.timer=null;this.tick().finally(()=>this.schedule())},this.pollMs)}
  async tick() {
    await Promise.all([...this.services.values()].map(async s=>{
      if(this.stopped||s.dead)return
      const child=s.child;let ok=false;try{ok=await this.health(s.kind,s.port)}catch{}
      if(this.stopped||s.child!==child||s.dead)return
      if(ok){s.failures=0;s.state='ready';s.reason='';if(s.readySince===null)s.readySince=this.now();if(this.now()-s.readySince>=this.stableMs)s.attempts=0}
      else {s.readySince=null;s.failures++;if(s.failures>=3||this.now()-s.started>=this.startupMs){s.state='unhealthy';s.reason='health_unavailable'}}
    }));if(!this.stopped)this.publish()
  }
  queue(s) {
    if(!s.restartable||this.stopped||s.recovery||s.timer||s.attempts>=this.backoff.length)return
    s.state='recovering';s.timer=setTimeout(()=>{s.timer=null;this.recover(s)},this.backoff[s.attempts]);this.publish()
  }
  recover(s) {
    if(s.recovery)return s.recovery
    if(this.stopped||!s.dead)return Promise.resolve(false)
    const operation=(async()=>{
      s.state='recovering';s.attempts++;this.publish()
      if(!await this.canBind(s.port)){if(!this.stopped){s.state='failed';s.reason='port_in_use'}return false}
      if(this.stopped)return false
      let child;try{child=s.spawn()}catch{s.state='failed';s.reason='spawn_failed';return false}
      s.child=child;s.state='starting';s.reason='';s.failures=0;s.started=this.now();s.readySince=null;this.attach(s,child);return true
    })()
    s.recovery=operation
    return operation.finally(()=>{s.recovery=null;if(!this.stopped){this.publish();if(s.dead&&s.reason!=='port_in_use')this.queue(s)}})
  }
  retry(kind) {
    const s=this.services.get(kind)
    if(!s||!s.restartable||this.stopped||!s.dead)return Promise.resolve(false)
    if(s.timer){clearTimeout(s.timer);s.timer=null}
    if(s.recovery)return s.recovery
    s.attempts=0;return this.recover(s)
  }
  stop(){this.stopped=true;clearTimeout(this.timer);this.timer=null;for(const s of this.services.values()){clearTimeout(s.timer);s.timer=null;s.state='stopped'}}
}
module.exports={LocalServiceSupervisor}
