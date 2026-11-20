import { releaseAppSource } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
test('panel snapshot updates sidebar immediately and rejects an older poll response',async()=>{
 const s=releaseAppSource();const body=s.slice(s.indexOf('let dailyRequest=0'),s.indexOf('async function initializeCompanion'))
 let resolve;const dailyStatus={value:{unread:true}},me={value:{id:1}}
 const f=new Function('dailyStatus','me','json',body+';return {loadDailyStatus,acceptDailyStatus}')(dailyStatus,me,()=>new Promise(r=>resolve=r))
 const old=f.loadDailyStatus();f.acceptDailyStatus({unread:false});assert.equal(dailyStatus.value.unread,false);resolve({unread:true});await old;assert.equal(dailyStatus.value.unread,false)
})
