import {test} from 'node:test'
import assert from 'node:assert/strict'
import {speechChunks} from '../src/release/speechChunks.mjs'
test('short introductions wait for the following sentence instead of breaking Astra synthesis',()=>{
 const first=speechChunks('我是黍。');assert.deepEqual(first,{chunks:[],rest:'我是黍。'});const next=speechChunks(first.rest+'我们一起去看看田里的庄稼吧。');assert.deepEqual(next.chunks,['我是黍。我们一起去看看田里的庄稼吧。']);assert.equal(next.rest,'')
})
test('finished short replies are retained and narration alone is skipped',()=>{
 assert.deepEqual(speechChunks('嗯。',true).chunks,['嗯。']);assert.deepEqual(speechChunks('（轻轻点头。）',true).chunks,[]);assert.equal(speechChunks('你好，',false).rest,'你好，')
})
