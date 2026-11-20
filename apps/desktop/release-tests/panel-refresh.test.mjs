import { releaseAppSource } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import {createPanelRefresh} from '../src/release/panelRefresh.mjs'
import {readFileSync} from 'node:fs'
test('closed panel rejects late response; reopened request remains valid',async()=>{
 const pending=[];const scope=createPanelRefresh(()=>new Promise(resolve=>pending.push(resolve)))
 let displayed='';const old=scope.request()('old').then(v=>displayed=v).catch(()=>{})
 scope.invalidate();const fresh=scope.request()('new').then(v=>displayed=v)
 pending[1]('current');await fresh;pending[0]('stale');await old;assert.equal(displayed,'current')
})
test('data reset clears both draft stores before any asynchronous reload',()=>{
 const s=releaseAppSource();const body=s.match(/async function refreshDataState\(\)\{([^\n]+)/)[1]
 const calls=[];const prefix=body.slice(0,body.indexOf('speech.stop()'))
 new Function('sendDraftRecovery','draftRecovery','pendingContinuation','resetCharacterImport',prefix)({clear(){calls.push('send')}},{clear(){calls.push('draft')}},{value:null},()=>{})
 assert.deepEqual(calls,['send','draft'])
})
test('feedback list refresh updates interests and delivery without overwriting unsaved settings',async()=>{
 const s=readFileSync(new URL('../src/release/CompanionPanel.vue',import.meta.url),'utf8')
 const fn=s.match(/async function refreshLists\(\)\{[^\n]+/)[0]
 const pending=[];const lifecycle=createPanelRefresh(path=>new Promise(resolve=>pending.push({path,resolve})))
 const f=new Function('lifecycle',`let listVersion=0;const props={conversationId:'c'},interests={value:[]},candidates={value:[]},settings={value:{city:'未保存'}};${fn};return {refreshLists,interests,candidates,settings}`)(lifecycle)
 const first=f.refreshLists(),second=f.refreshLists()
 pending[2].resolve([{topic:'新主题'}]);pending[3].resolve([{status:'delivered'}]);await second
 pending[0].resolve([{topic:'旧主题'}]);pending[1].resolve([{status:'pending'}]);await first
 assert.equal(f.interests.value[0].topic,'新主题');assert.equal(f.candidates.value[0].status,'delivered');assert.equal(f.settings.value.city,'未保存')
})
