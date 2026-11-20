const fs = require('fs'), path = require('path'), crypto = require('crypto'), net = require('net'), { spawn } = require('child_process')
const {embeddingArgs}=require('./embedding-args.cjs')
const {LocalServiceSupervisor}=require('./local-service-supervisor.cjs')
const { installPack, verifyPack } = require('./model-pack.cjs')
const freePort = () => new Promise((resolve, reject) => {
  const server = net.createServer()
  server.on('error', reject)
  server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)) })
})
class OfflineRunner {
  constructor({ resources, data, java, jar, database }) {
    this.resources = resources; this.data = data; this.java = java; this.jar = jar; this.database = database; this.processes = []; this.generation = 0
    this.token = crypto.randomBytes(32).toString('base64url')
    this.publicKey = fs.readFileSync(path.join(__dirname, 'model-pack-public.pem'))
    this.modelState = 'missing'; this.modelError = ''; this.modelProgress = ''
  }
  launch(binary, args, extraEnv, name) {
    if (!fs.existsSync(binary)) throw new Error('离线运行时缺少 ' + name + '，请重新安装应用')
    const logs = path.join(this.data, 'logs'); fs.mkdirSync(logs, { recursive: true })
    const fd = fs.openSync(path.join(logs, name + '.log'), 'a')
    const child = spawn(binary, args, { stdio: ['ignore', fd, fd], windowsHide: true, env: { ...process.env, HF_HUB_OFFLINE: '1', TRANSFORMERS_OFFLINE: '1', HF_DATASETS_OFFLINE: '1', DO_NOT_TRACK: '1', ...extraEnv } })
    fs.closeSync(fd); child.on('error', () => {}); this.processes.push(child); return child
  }
  async monitor(services, generation) {
    for (let i = 0; i < 300 && generation === this.generation; i++) {
      const stopped = services.find(service => {const child=this.supervisor?.services.get(service.kind)?.child||service.child;return child.exitCode !== null || child.signalCode})
      if(stopped&&this.supervisor?.services.get(stopped.kind)?.state==='recovering'){await new Promise(resolve=>setTimeout(resolve,500));continue}
      if (stopped) { this.modelState = 'error'; this.modelError = stopped.name + '未能启动。请重新导入完整模型包；如仍失败，请重新安装离线版。'; this.modelProgress = this.modelError; return false }
      const checks = await Promise.all(services.map(async ({port}) => {
        try { return (await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(1000), headers: { Authorization: 'Bearer ' + this.token } })).ok } catch { return false }
      }))
      this.modelProgress = checks.every(Boolean) ? '本次本地服务已就绪' : '正在启动：' + services.filter((service, index) => !checks[index]).map(service => service.name).join('、')
      if (checks.every(Boolean)) { this.modelState = 'ready'; return true }
      await new Promise(resolve => setTimeout(resolve, 500))
    }
    if (generation === this.generation) { this.modelState = 'error'; this.modelError = this.modelProgress.replace('正在启动：', '') + '准备超时。请重新导入完整模型包；如仍失败，请重新安装离线版。' }
    return false
  }
  async start({ waitForModels = false } = {}) {
    const generation = ++this.generation
    this.supervisor?.stop()
    this.supervisor=new LocalServiceSupervisor({
      health:async(kind,port)=>{try{return (await fetch(`http://127.0.0.1:${port}/${kind==='backend'?'api/health':'health'}`,{signal:AbortSignal.timeout(1000),headers:{Authorization:'Bearer '+this.token}})).ok}catch{return false}},
      onExit:event=>{try{const dir=path.join(this.data,'logs');fs.mkdirSync(dir,{recursive:true});const file=path.join(dir,'service-exits.jsonl');if(fs.existsSync(file)&&fs.statSync(file).size>65536)fs.renameSync(file,file+'.previous');fs.appendFileSync(file,JSON.stringify(event)+'\n')}catch{}}
    })
    fs.mkdirSync(this.data, { recursive: true }); this.modelError = ''; this.modelProgress = '正在校验已安装的模型文件'
    const [port, llmPort, embedPort, voicePort] = await Promise.all([freePort(), freePort(), freePort(), freePort()])
    this.base = 'http://127.0.0.1:' + port
    const modelRoot = path.join(this.data, 'models')
    const manifest = fs.existsSync(path.join(modelRoot, 'manifest.json')) ? await verifyPack(modelRoot, this.publicKey) : null
    this.hasManifest=!!manifest
    this.modelState = manifest ? 'starting' : 'missing'; if (!manifest) this.modelProgress = '尚未导入离线模型包'
    const suffix = process.platform === 'win32' ? '.exe' : '', bin = path.join(this.resources, 'bin')
    const env = { STORY_LLM_KEY: this.token, STORY_EMBEDDING_KEY: this.token, STORY_VOICE_KEY: this.token, STORY_LLM_URL: `http://127.0.0.1:${llmPort}/v1`, STORY_EMBEDDING_URL: `http://127.0.0.1:${embedPort}/v1`, STORY_ASR_URL: `http://127.0.0.1:${voicePort}/v1/audio/transcriptions`, STORY_TTS_URL: `http://127.0.0.1:${voicePort}/v1/audio/speech` }
    for(const key of Object.keys(env))if(process.env[key])env[key]=process.env[key]
    for(const key of ['STORY_LLM_MODEL','STORY_EMBEDDING_MODEL','STORY_ASTRA_URL','STORY_TTS_ENGINE'])if(process.env[key])env[key]=process.env[key]
    env.STORY_TTS_ENGINE=process.env.STORY_TTS_ENGINE || (process.env.STORY_ASTRA_URL ? 'astra' : manifest?.models.tts ? 'kokoro' : 'astra')
    this.externalModels=!!process.env.STORY_LLM_URL
    if(!manifest&&this.externalModels)this.modelState='ready'
    if (manifest) {
      const llm = manifest.models.llm, embedding = manifest.models.embedding
      const common = ['--host', '127.0.0.1', '--no-webui', '--api-key', this.token]
      const backendEnv = { GGML_BACKEND_PATH: path.join(this.resources, 'lib') }
      const localPorts=[]
      if(!process.env.STORY_LLM_URL){const spawn=()=>this.launch(path.join(bin, 'llama-server' + suffix), [...common, '--port', String(llmPort), '--model', path.join(modelRoot, llm.file), '--alias', llm.name, '--ctx-size', '8192', '--parallel', '1'], backendEnv, 'llm');const child=spawn();this.supervisor.add('llm',llmPort,child,spawn);env.STORY_LLM_MODEL=llm.name;localPorts.push({kind:'llm',port:llmPort,name:'聊天模型',child})}
      if(!process.env.STORY_EMBEDDING_URL){const spawn=()=>this.launch(path.join(bin, 'llama-server' + suffix), embeddingArgs(common,embedPort,path.join(modelRoot,embedding.file),embedding.name), backendEnv, 'embedding');const child=spawn();this.supervisor.add('embedding',embedPort,child,spawn);env.STORY_EMBEDDING_MODEL=embedding.name;localPorts.push({kind:'embedding',port:embedPort,name:'记忆模型',child})}
      if (manifest.models.asr && manifest.models.tts && (!process.env.STORY_ASR_URL || env.STORY_TTS_ENGINE==='kokoro' && !process.env.STORY_TTS_URL)){const spawn=()=>this.launch(path.join(this.resources, 'voice', 'story-voice' + suffix), ['--models', modelRoot, '--port', String(voicePort)], { PATH: bin + path.delimiter + process.env.PATH, STORY_VOICE_KEY: env.STORY_VOICE_KEY }, 'voice');const child=spawn();this.supervisor.add('voice',voicePort,child,spawn);localPorts.push({kind:'voice',port:voicePort,name:'语音服务',child})}
      this.ready = this.monitor(localPorts, generation)
    }
    const child = this.launch(this.java || path.join(this.resources, 'java', 'bin', 'java' + suffix), ['-Xmx512m', '-jar', this.jar || path.join(this.resources, 'story-runtime.jar'), '--spring.profiles.active=offline','--story.mode=offline','--server.address=127.0.0.1'], { ...env, STORY_DATA: this.data, STORY_SQLITE_FILE: this.database || path.join(this.data, 'stories.sqlite'), STORY_PORT: String(port), STORY_BIND: '127.0.0.1', STORY_LOCAL_TOKEN: this.token }, 'backend')
    this.supervisor.add('backend',port,child,null,false)
    for (let i = 0; i < 120; i++) {
      if (child.exitCode !== null) throw new Error('本地服务启动失败，请查看 ' + path.join(this.data, 'logs/backend.log'))
      try { if ((await fetch(this.base + '/api/health', { signal: AbortSignal.timeout(1000) })).ok) { if (waitForModels && manifest && !await this.ready) throw new Error(this.modelError); return } } catch (e) { if (this.modelState === 'error') throw e }
      await new Promise(resolve => setTimeout(resolve, 500))
    }
    throw new Error('本地服务启动超时，请查看日志')
  }
  async install(source) {
    this.modelProgress = '正在校验并复制模型文件'
    const target = path.join(this.data, 'models'), pack = await installPack(source, target, this.publicKey)
    this.stop(); const old = target + '.previous'; fs.rmSync(old, { recursive: true, force: true })
    if (fs.existsSync(target)) fs.renameSync(target, old)
    fs.renameSync(pack.temporary, target)
    try { await this.start({ waitForModels: true }); fs.rmSync(old, { recursive: true, force: true }) }
    catch (e) { this.stop(); fs.rmSync(target, { recursive: true, force: true }); if (fs.existsSync(old)) fs.renameSync(old, target); await this.start(); throw e }
  }
  runtimeModelState(){const services=this.serviceStatus(),states=Object.values(services).map(s=>s.state);if(this.hasManifest&&states.every(s=>s==='ready'))return 'ready';return states.some(s=>s==='recovering')?'recovering':states.some(s=>['failed','unhealthy'].includes(s))?'degraded':this.modelState}
  serviceStatus(){return this.supervisor?.snapshot()||{}}
  retryService(kind){if(!['llm','embedding','voice'].includes(kind))throw new Error('不支持的本地服务');return this.supervisor?.retry(kind)||Promise.resolve(false)}
  stop() { this.supervisor?.stop(); this.generation++; for (const child of this.processes) { try { child.kill() } catch {} } this.processes = [] }
}
module.exports = { OfflineRunner }
