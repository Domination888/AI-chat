import test from 'node:test'
import assert from 'node:assert/strict'
import {failureDescription} from '../src/release/failureCategory.mjs'
test('safe fixed labels explain actionable categories without reflecting arbitrary data',()=>{
 assert.match(failureDescription('upstream_auth_failed'),/凭据/)
 assert.match(failureDescription('network_timeout'),/超时/)
 assert.match(failureDescription('storage_failed'),/数据库/)
 assert.match(failureDescription('user_interrupted'),/不代表服务故障/)
 assert.match(failureDescription('failed'),/无法据此判断/)
 assert.doesNotMatch(failureDescription('secret-token https://private'),/secret|https/)
 for(const key of ['constructor','toString','__proto__'])assert.match(failureDescription(key),/原因未细分/)
})
