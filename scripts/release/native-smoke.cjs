// End-to-end native model evaluation in an isolated local directory. No user stories are read.
const path=require('path'),fs=require('fs'),os=require('os'),assert=require('assert/strict'),{execFileSync}=require('child_process')
const {OfflineRunner}=require('../../apps/desktop/electron/offline-runner.cjs')
const root=path.resolve(__dirname,'../..'),data=fs.mkdtempSync(path.join(os.tmpdir(),'story-native-'))
const models=path.join(root,'packaging/release/model-packs/standard')
function clone(source,target){fs.mkdirSync(target,{recursive:true});for(const entry of fs.readdirSync(source,{withFileTypes:true})){const a=path.join(source,entry.name),b=path.join(target,entry.name);if(entry.isDirectory())clone(a,b);else fs.linkSync(a,b)}}
clone(models,path.join(data,'models'))
const runner=new OfflineRunner({resources:path.join(root,'packaging/release/staging',process.platform),data})
const result={platform:process.platform,hostMemoryBytes:Number(execFileSync('sysctl',['-n','hw.memsize'],{encoding:'utf8'})),turns:[],memoryMeasurement:'sum of process RSS; GPU allocations are not included'}
async function request(route,body,method='GET'){const r=await fetch(runner.base+route,{method,headers:{Authorization:'Bearer '+runner.token,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});if(!r.ok)throw new Error(route+' failed: '+await r.text());return r}
const rss=()=>{try{return runner.processes.reduce((sum,p)=>sum+Number(execFileSync('ps',['-o','rss=','-p',String(p.pid)],{encoding:'utf8'}).trim()||0)*1024,0)}catch{return 0}}
;(async()=>{
 const boot=performance.now();await runner.start({waitForModels:true});result.startupMs=performance.now()-boot;let peak=0;const timer=setInterval(()=>{peak=Math.max(peak,rss())},1000)
 try{
  const roles=await (await request('/api/roles')).json();const role=roles[0].id,conv='conv_native_'+Date.now();await request('/api/rp/sessions',{conversationId:conv,roleId:role,state:{location:'书店',relationship:'初识',style:'chat',directorNote:'回答保持一句到三句，保持场景连续。'}},'POST')
  const count=Number(process.env.STORY_EVAL_TURNS || 30)
  for(let i=0;i<count;i++){
   const message=i===0?'我叫青禾。我送给你一本蓝色封面的诗集，请记住这个约定：下次我们一起读它。':i===count-1?'还记得我叫什么，以及我送给你的那本书是什么颜色吗？':`我们还在书店。聊聊第 ${i+1} 个轻松的话题，用一句话回答。`
   const start=performance.now(),r=await request('/api/chat',{conversationId:conv,roleId:role,message},'POST');let text='',first=null,done=false,buffer='',error='';const decoder=new TextDecoder()
   for await(const bytes of r.body){buffer+=decoder.decode(bytes,{stream:true});const frames=buffer.split('\n\n');buffer=frames.pop();for(const frame of frames){const event=frame.match(/^event:(.*)$/m)?.[1]?.trim(),value=frame.match(/^data:(.*)$/m)?.[1];if(!value)continue;const payload=JSON.parse(value);if(event==='text'){first ??= performance.now()-start;text+=payload.text}if(event==='done')done=true;if(event==='error')error=payload.message}}
   assert.equal(error,'');assert.ok(done);assert.ok(text.trim());assert.ok(!text.includes('<scene>'));result.turns.push({turn:i+1,firstTokenMs:first,totalMs:performance.now()-start,text});if((i+1)%5===0)console.log('Native turns complete:',i+1)
  }
  result.recallEvidence=result.turns.at(-1).text;result.recallPassed=/青禾/.test(result.recallEvidence)&&/蓝/.test(result.recallEvidence)
  const speech=await request('/api/v1/voice/speech',{text:'你好，青禾。我们下次一起读这本蓝色封面的诗集。'},'POST');const audio=Buffer.from(await speech.arrayBuffer());assert.ok(audio.length>1000);assert.equal(audio.subarray(0,4).toString(),'RIFF');result.ttsBytes=audio.length
  const r=await request('/api/chat',{conversationId:conv,roleId:role,inputMode:'audio',audioFormat:'wav',audioBase64:audio.toString('base64')},'POST');const asr=await r.text();assert.ok(asr.includes('event:asr'));assert.ok(asr.includes('event:done'));result.asrPassed=true
  result.peakRssBytes=peak;result.success=true
 }finally{clearInterval(timer)}
})().catch(e=>{result.success=false;result.error=e.message;process.exitCode=1}).finally(()=>{runner.stop();fs.mkdirSync(path.join(root,'artifacts'),{recursive:true});fs.writeFileSync(path.join(root,'artifacts/native-evaluation.json'),JSON.stringify(result,null,2));console.log('Native evaluation:',result.success?'PASS':'FAIL',result.error||'');console.log('Evidence: artifacts/native-evaluation.json')})
