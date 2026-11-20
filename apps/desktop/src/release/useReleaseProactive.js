import {json,request} from './api.mjs'
import {readSse} from '../utils/sse.js'
export function useReleaseProactive({onEvent,onError}){
 let controller=null,conversation='',version=0,timer=null,lastTouch=0
 async function detach(notify=true){version++;controller?.abort();controller=null;clearTimeout(timer);const old=conversation;conversation='';if(old&&notify)await json('/api/chat/proactive',{conversationId:old},'DELETE').catch(()=>{})}
 async function attach(value){await detach();if(!value)return;conversation=value;const seq=version
  async function connect(){if(seq!==version)return;controller=new AbortController();try{
   await json('/api/chat/proactive',{conversationId:value},'POST');const response=await request(`/api/chat/proactive/stream?conversationId=${encodeURIComponent(value)}`,{signal:controller.signal});if(!response.ok)throw new Error('主动对话连接失败')
   for await(const frame of readSse(response.body,controller.signal)){if(seq!==version)return;onEvent(frame.event,frame.payload)}
  }catch(e){if(e.name==='AbortError')return;if(seq===version)onError?.(e)}finally{if(seq===version)timer=setTimeout(connect,3000)}}
  connect()
 }
 async function activity(){if(!conversation||Date.now()-lastTouch<3000)return;lastTouch=Date.now();await json('/api/chat/proactive/activity',{conversationId:conversation},'POST').catch(()=>{})}
 async function trigger(){if(!conversation)return;return json('/api/chat/proactive/trigger',{conversationId:conversation},'POST')}
 return {attach,detach,activity,trigger}
}
