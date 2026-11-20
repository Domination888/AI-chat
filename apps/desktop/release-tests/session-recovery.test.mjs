import { releaseAppSource, runReleaseSnippet } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import {createSessionDraftRecovery} from '../src/release/sessionDraft.mjs'
import {useSendDraftRecovery} from '../src/release/sendDraftRecovery.mjs'
const source=releaseAppSource()
const ref=value=>({value})
test('unsent text and images survive expiry only for the same account and server',()=>{
 const drafts=createSessionDraftRecovery()
 const remember=()=>drafts.remember({userId:4,base:'https://story.test',conversationId:'chapter',roleId:2,text:'继续补充',images:['data:image/png;base64,new']},{message:'未发送的问题',images:['data:image/png;base64,old'],accepted:false})
 remember();const same=drafts.take(4,'https://story.test');assert.equal(same.text,'未发送的问题\n\n继续补充');assert.deepEqual(same.images,['data:image/png;base64,old','data:image/png;base64,new'])
 remember();assert.equal(drafts.take(5,'https://story.test'),null)
 remember();assert.equal(drafts.take(4,'https://other.test'),null)
 assert.equal(drafts.take(4,'https://story.test'),null)
})
test('accepted requests are not put back into the composer and repeated expiry does not lose drafts',()=>{
 const drafts=createSessionDraftRecovery(),current={userId:4,base:'https://story.test',conversationId:'chapter',text:'还没发的补充',images:['new']}
 drafts.remember(current,{message:'服务器已接收',images:['accepted'],accepted:true})
 drafts.remember({...current,conversationId:'',text:'',images:[]},null)
 assert.deepEqual(drafts.take(4,'https://story.test'),{...current,roleId:undefined})
})
function fixture(){
 const calls=[],context={pendingContinuation:ref(null),pendingDailyIssue:ref(null),offline:false,installingModels:ref(false),offlineModelMissing:ref(false),disposed:false,generation:3,selection:2,controller:{abort(){calls.push('abort')}},pendingSend:{message:'未发送的问题',images:['data:image/png;base64,photo'],accepted:false},proactiveIndex:1,draftRecovery:createSessionDraftRecovery(),apiBase:()=> 'https://story.test',speech:{stop(){calls.push('speech')}},cancelRecording(){calls.push('recording')},locationController:{abort(){}},proactive:{detach(notify){calls.push(['detach',notify])}},clearInterval(){},dailyTimer:1,settleAction(){},clearNotice(){},nextTick:()=>{},characterImported:true}
 for(const key of ['generating','stopping','accountOpen','showScene','toolsOpen','companionOpen','showRoleForm','register'])context[key]=ref(true)
 Object.assign(context,{me:ref({id:4}),selected:ref({id:2}),conversation:ref('chapter'),messages:ref([{role:'user',content:'未发送的问题'},{role:'ai',content:''}]),chapters:ref([{id:'chapter',role_id:2}]),roles:ref([{id:2}]),text:ref(''),images:ref([]),page:ref('story'),credentials:ref({password:'old-password'}),error:ref(''),authNotice:ref('')})
 context.sendDraftRecovery=useSendDraftRecovery({scope:()=>({userId:context.me.value?.id,base:context.apiBase(),conversationId:context.conversation.value,inStory:context.page.value==='story'}),readDraft:()=>({text:context.text.value,images:context.images.value}),writeDraft:draft=>{context.text.value=draft.text;context.images.value=draft.images}})
 const code=source.slice(source.indexOf('function handleSessionExpired(){'),source.indexOf('\nconst removeSessionExpired='))
 return {context,calls,expire:runReleaseSnippet(`${code}\nhandleSessionExpired`,context)}
}
test('session expiry replaces the chat with login and removes optimistic blank messages',()=>{
 const {context,calls,expire}=fixture();expire()
 assert.equal(context.me.value,null);assert.equal(context.conversation.value,'');assert.equal(context.messages.value.length,0);assert.equal(context.generating.value,false);assert.equal(context.stopping.value,false)
 assert.equal(context.credentials.value.password,'');assert.match(context.authNotice.value,/重新登录/);assert.match(context.authNotice.value,/请勿刷新/)
 assert.ok(calls.includes('abort'));assert.deepEqual(calls.find(c=>Array.isArray(c)),['detach',false])
 const saved=context.draftRecovery.take(4,'https://story.test');assert.equal(saved.text,'未发送的问题');assert.deepEqual(saved.images,['data:image/png;base64,photo'])
})
test('same-account login restores the chapter and draft without automatically sending it',async()=>{
 const {context,calls,expire}=fixture();expire()
 Object.assign(context,{guarded:fn=>fn(),json:async path=>{assert.equal(path,'/api/v1/auth/login');return {user:{id:4}}},acceptSession:async s=>s.user,loadLibrary:async()=>{context.chapters.value=[{id:'chapter',role_id:2}];context.roles.value=[{id:2}]},initializeCompanion:async()=>{},loadMessages:async()=>calls.push('history'),loadConversationState:async()=>calls.push('conversation-state'),toast:message=>calls.push(message)})
 const code=source.slice(source.indexOf('async function authenticate(){'),source.indexOf('\nasync function loadChapters()'))
 await runReleaseSnippet(`${code}\nauthenticate`,context)()
 assert.equal(context.conversation.value,'chapter');assert.equal(context.text.value,'未发送的问题');assert.deepEqual(context.images.value,['data:image/png;base64,photo']);assert.equal(context.authNotice.value,'');assert.ok(calls.includes('history'));assert.ok(calls.includes('conversation-state'))
})
test('a real send-path 401 saves its cleared composer draft and never leaves the blank reply behind',async()=>{
 const {context,calls,expire}=fixture()
 context.text.value='请看看这张照片';context.images.value=['data:image/png;base64,photo']
 Object.assign(context,{AbortController,speech:{...context.speech,unlock:async()=>{}},proactive:{...context.proactive,activity:async()=>{}},stop:async()=>{},bottom:async()=>{},webSearch:ref(false),isSessionExpired:e=>e.code==='SESSION_EXPIRED',toast:message=>calls.push(['toast',message]),loadMessages:async()=>calls.push('unexpected-history'),loadChapters:async()=>calls.push('unexpected-chapters'),request:async(path,init)=>{assert.equal(path,'/api/chat');assert.equal(JSON.parse(init.body).message,'请看看这张照片');expire();throw Object.assign(new Error('请重新登录'),{code:'SESSION_EXPIRED'})}})
 const code=source.slice(source.indexOf('async function send(audioPayload)'),source.indexOf('\nconst {isRecording'))
 await runReleaseSnippet(`${code}\nsend`,context)()
 assert.equal(context.messages.value.length,0);assert.equal(context.me.value,null);assert.ok(!calls.includes('unexpected-history'))
 const draft=context.draftRecovery.take(4,'https://story.test');assert.equal(draft.text,'请看看这张照片');assert.deepEqual(draft.images,['data:image/png;base64,photo'])
})
