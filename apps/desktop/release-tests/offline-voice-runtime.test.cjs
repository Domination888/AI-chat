const {EventEmitter}=require('node:events')
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto')
const {OfflineRunner}=require('../electron/offline-runner.cjs')
async function startWith(overrides,check) {
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'story-voice-runtime-')),models=path.join(dir,'models'),originalFetch=global.fetch
  const names=['STORY_LLM_URL','STORY_EMBEDDING_URL','STORY_ASR_URL','STORY_TTS_URL','STORY_ASTRA_URL','STORY_TTS_ENGINE','STORY_VOICE_KEY'],previous=Object.fromEntries(names.map(k=>[k,process.env[k]]))
  let runner
  const children=[]
  try {
    fs.mkdirSync(models);const files=[]
    for(const name of ['chat.gguf','embedding.gguf','asr.onnx','tts.onnx']){const bytes=Buffer.from(name);fs.writeFileSync(path.join(models,name),bytes);files.push({path:name,size:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex')})}
    const metadata=file=>({file,source:'unit-test-fixture',license:'test'}),manifest={format:'story-model-pack-v1',files,models:{llm:{...metadata('chat.gguf'),name:'chat'},embedding:{...metadata('embedding.gguf'),name:'embed'},asr:metadata('asr.onnx'),tts:metadata('tts.onnx')}}
    const raw=Buffer.from(JSON.stringify(manifest)),keys=crypto.generateKeyPairSync('ed25519');fs.writeFileSync(path.join(models,'manifest.json'),raw);fs.writeFileSync(path.join(models,'manifest.sig'),crypto.sign(null,raw,keys.privateKey))
    names.forEach(k=>delete process.env[k]);Object.assign(process.env,overrides)
    global.fetch=async()=>({ok:true})
    const launches=[];runner=new OfflineRunner({resources:dir,data:dir,java:'test-java',jar:'test.jar'});runner.publicKey=keys.publicKey
    runner.launch=(binary,args,env,name)=>{
      launches.push({binary,args,env,name})
      const child=new EventEmitter();child.exitCode=null;child.signalCode=null
      child.kill=()=>{if(child.exitCode!==null||child.signalCode)return false;child.signalCode='SIGTERM';child.emit('exit',null,'SIGTERM');return true}
      children.push(child);runner.processes.push(child);return child
    }
    runner.monitor=async()=>{runner.modelState='ready';return true}
    await runner.start({waitForModels:true});await check(launches,runner)
  } finally {runner?.stop();if(runner?.supervisor)assert.equal(runner.supervisor.stopped,true);assert.ok(children.every(child=>child.signalCode==='SIGTERM'));global.fetch=originalFetch;for(const k of names){if(previous[k]===undefined)delete process.env[k];else process.env[k]=previous[k]}fs.rmSync(dir,{recursive:true,force:true})}
}
test('a signed pack defaults to its bundled Kokoro voice service and matching authentication',async()=>{
  await startWith({},(launches)=>{
    const embedding=launches.find(x=>x.name==='embedding'),llm=launches.find(x=>x.name==='llm')
    const value=(args,flag)=>args[args.indexOf(flag)+1]
    assert.equal(value(embedding.args,'--parallel'),'1')
    assert.equal(value(embedding.args,'--pooling'),'last')
    const context=Number(value(embedding.args,'--ctx-size'))
    assert.equal(context,4096);assert.ok(Number(value(embedding.args,'--batch-size'))>=context);assert.ok(Number(value(embedding.args,'--ubatch-size'))>=context)
    assert.equal(value(llm.args,'--ctx-size'),'8192');assert.equal(value(llm.args,'--parallel'),'1')
    assert.ok(!llm.args.includes('--batch-size')&&!llm.args.includes('--ubatch-size'))
    assert.ok(!launches.find(x=>x.name==='voice').args.includes('--ctx-size'))
    const voice=launches.find(x=>x.name==='voice'),backend=launches.find(x=>x.name==='backend');assert.ok(voice);assert.equal(backend.env.STORY_TTS_ENGINE,'kokoro');assert.equal(voice.env.STORY_VOICE_KEY,backend.env.STORY_VOICE_KEY);assert.match(backend.env.STORY_TTS_URL,/\/v1\/audio\/speech$/)})
})
test('explicit Astra and external recognition are preserved without starting unused packaged speech',async()=>{
  await startWith({STORY_ASTRA_URL:'http://127.0.0.1:5000',STORY_ASR_URL:'http://127.0.0.1:12347/v1/audio/transcriptions'},launches=>{const backend=launches.find(x=>x.name==='backend');assert.equal(backend.env.STORY_TTS_ENGINE,'astra');assert.equal(backend.env.STORY_ASTRA_URL,'http://127.0.0.1:5000');assert.equal(launches.some(x=>x.name==='voice'),false)})
})
test('local bundled speech uses the configured voice key when recognition is external',async()=>{
  await startWith({STORY_ASR_URL:'http://127.0.0.1:12347/v1/audio/transcriptions',STORY_VOICE_KEY:'unit-test-custom-key'},launches=>{const voice=launches.find(x=>x.name==='voice'),backend=launches.find(x=>x.name==='backend');assert.ok(voice);assert.equal(backend.env.STORY_TTS_ENGINE,'kokoro');assert.equal(voice.env.STORY_VOICE_KEY,'unit-test-custom-key');assert.equal(backend.env.STORY_VOICE_KEY,'unit-test-custom-key')})
})
