import { releaseAppSource } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import {localServiceNotices,localChatBlocked} from '../src/release/localServices.mjs'
import {failureDescription} from '../src/release/failureCategory.mjs'
import vm from 'node:vm'
import * as Vue from 'vue'
import {compile} from '@vue/compiler-dom'
import {parse,compileScript} from '@vue/compiler-sfc'
test('embedding degradation preserves chatting and reports accurate recoverable status',()=>{
 const info={modelState:'degraded',localServices:{llm:{state:'ready'},embedding:{state:'failed',attempts:3,retryAvailable:true}}}
 assert.equal(localChatBlocked(info,false),false);assert.equal(localServiceNotices(info)[0].retryAvailable,true)
 assert.match(localServiceNotices(info)[0].text,/记忆索引服务已停止/)
 info.localServices.llm.state='recovering';assert.equal(localChatBlocked(info,false),true);assert.equal(localChatBlocked(info,true),false)
 assert.equal(localChatBlocked({modelState:'missing'},false),true)
 assert.equal(localChatBlocked({externalModels:true,modelState:'degraded'},false),false)
})
test('administrator suspension is distinct from voluntary user stopping',()=>{
 assert.match(failureDescription('account_suspended'),/管理员/);assert.doesNotMatch(failureDescription('account_suspended'),/用户已停止/)
 for(const code of ['account_deleted','client_disconnected','request_timeout','application_shutdown','operation_interrupted'])assert.doesNotMatch(failureDescription(code),/原因未细分|用户已停止/)
})
test('compiled local status retry button invokes actual handler without changing draft or chapter',async()=>{
 const source=releaseAppSource()
 compileScript(parse(source).descriptor,{id:'recovery-test'})
 const template=source.match(/<section v-if="offline" aria-label="本地服务状态">.*?<\/section>/)[0]
 const handler=source.split('\n').find(line=>line.startsWith('async function retryLocalService('))
 const text=Vue.ref('未发的草稿'),images=Vue.ref(['fixture-image']),conversation=Vue.ref('same-chapter'),calls=[]
 const info={localServices:{embedding:{state:'failed',retryAvailable:true}}}
 const c={localRetryBusy:Vue.ref(false),nativeInfo:Vue.ref(info),text,images,conversation,toast:()=>{},window:{storyDesktop:{retryLocalService:async kind=>calls.push(kind),bootstrap:async()=>({localServices:{embedding:{state:'starting'}}})}}}
 const retry=vm.runInNewContext(handler+';retryLocalService',c)
 const render=new Function('Vue',compile(template,{mode:'function'}).code)(Vue)
 const ctx={offline:true,nativeInfo:info,localServiceNotices,localRetryBusy:false,retryLocalService:retry}
 const node=render.call(ctx,ctx,[]);let button
 function walk(v){if(!v||typeof v!=='object')return;if(v.type==='button')button=v;if(Array.isArray(v.children))v.children.forEach(walk)}walk(node)
 assert.ok(button);await button.props.onClick()
 assert.deepEqual(calls,['embedding']);assert.equal(c.localRetryBusy.value,false)
 assert.equal(text.value,'未发的草稿');assert.equal(images.value[0],'fixture-image');assert.equal(conversation.value,'same-chapter')
 assert.equal(c.nativeInfo.value.localServices.embedding.state,'starting')
})
