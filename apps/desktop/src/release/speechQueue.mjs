import {speechChunks} from './speechChunks.mjs'

// Dependencies are supplied by the Vue adapter so cancellation can be tested
// without a browser, network service, or audio device.
export function createSpeechQueue({speaking,enabled,roleId,onError,request,createContext,lipSync,phase={value:'idle'},synthesisTimeoutMs=60000}) {
  let context=null,current=null,controller=null,version=0,queue=[],running=false,pending='',done=null,failedVersion=-1
  async function unlock() {
    context ||= createContext()
    const audio=context
    await audio.resume()
    return audio
  }
  function stop() {
    version++
    controller?.abort();controller=null
    try { current?.stop() } catch {}
    current=null;done?.();done=null;queue=[];pending=''
    lipSync.stop();speaking.value=false;phase.value='idle'
  }
  async function synthesize(text,abort) {
    let timer,onAbort
    const cancelled=new Promise((resolve,reject)=>{
      onAbort=()=>reject(abort.signal.reason || new DOMException('朗读已取消','AbortError'))
      abort.signal.addEventListener('abort',onAbort,{once:true})
      timer=setTimeout(()=>abort.abort(new Error('语音合成等待超时，请检查语音服务后重新朗读。')),synthesisTimeoutMs)
    })
    // Bound the response body as well as the headers. A stalled service or a
    // request adapter that ignores abort must not keep the next turn waiting.
    const audio=(async()=>{
      const response=await request('/api/v1/voice/speech',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text,roleId:String(roleId())}),signal:abort.signal})
      if(!response.ok) {
        const data=await response.json().catch(()=>({}))
        throw new Error(data.message || '语音合成失败，请稍后重试')
      }
      return response.arrayBuffer()
    })()
    try {return await Promise.race([audio,cancelled])}
    finally {clearTimeout(timer);abort.signal.removeEventListener('abort',onAbort)}
  }
  async function drain() {
    if(running || !queue.length)return
    running=true
    const seq=version
    try {
      while(queue.length && seq===version) {
        speaking.value=true;phase.value='preparing'
        const text=queue.shift(),abort=new AbortController()
        controller=abort
        const bytes=await synthesize(text,abort)
        if(seq!==version)break
        const audio=await unlock()
        if(seq!==version)break
        const decoded=await audio.decodeAudioData(bytes)
        if(seq!==version)break
        const source=audio.createBufferSource()
        current=source;source.buffer=decoded;source.connect(audio.destination)
        lipSync.start(audio,source)
        await new Promise(resolve=>{done=resolve;source.onended=resolve;phase.value='playing';source.start()})
        if(seq!==version)break
        done=null;current=null;lipSync.stop()
      }
    } catch(error) {
      if(error.name!=='AbortError' && seq===version) {
        queue=[];pending='';failedVersion=seq;onError(error)
      }
    } finally {
      running=false
      if(seq===version){controller=null;current=null;speaking.value=false;phase.value='idle';lipSync.stop()}
      if(queue.length)drain()
    }
  }
  function addText(value,flush=false) {
    if(!enabled.value || failedVersion===version)return
    const result=speechChunks(pending+value,flush)
    pending=result.rest;queue.push(...result.chunks);drain()
  }
  async function read(text) {
    stop()
    const seq=version
    await unlock()
    if(seq!==version)return
    const chunks=speechChunks(text,true).chunks
    if(!chunks.length){onError(new Error('这段内容没有可朗读的台词'));return}
    queue.push(...chunks);drain()
  }
  function close(){stop();context?.close();context=null}
  return {speaking,phase,enabled,unlock,stop,addText,read,close}
}
