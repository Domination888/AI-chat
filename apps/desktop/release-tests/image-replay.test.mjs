import { releaseAppSource } from './release-app-fixture.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import {createServer} from 'node:http'
import {once} from 'node:events'
import {ref} from 'vue'
import {prepareReplayImages} from '../src/release/replayImages.mjs'

const source=releaseAppSource()
const code=source.slice(source.indexOf('async function replayPictures('),source.indexOf('async function bookmark('))
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aSfsAAAAASUVORK5CYII=','base64')

async function fixture(t,{unavailable=false}={}) {
  let removed=false
  const events=[],errors=[]
  const server=createServer((req,res)=>{
    events.push('read-image')
    if(removed||unavailable){res.writeHead(404);res.end();return}
    res.writeHead(200,{'Content-Type':'image/png'});res.end(png)
  })
  server.listen(0,'127.0.0.1');await once(server,'listening')
  t.after(()=>new Promise(resolve=>server.close(resolve)))
  const base=`http://127.0.0.1:${server.address().port}`
  const message={id:1,content:'原说明',images:[`${base}/api/attachments/picture/content?signature=test`]}
  const context={pendingContinuation:ref(null),pendingDailyIssue:ref(null),historyBusy:ref(false),conversation:ref('chapter'),text:ref(''),images:ref([]),messages:ref([message,{role:'ai'}]),apiBase:()=>base,prepareReplayImages,
    guarded:async fn=>{try{return await fn()}catch(e){errors.push(e.message)}},
    askAction:async()=> '修改后的说明',applyRewind:async()=>{events.push('rewind');removed=true;return true},
    regenerationAction:()=>({kind:'reply'}),rewindScope:'说明',toast:()=>{},nextTick:async()=>{},document:{querySelector:()=>null},send:async()=>events.push('send')}
  const actions=vm.runInNewContext(`${code};({editMessage,rewrite})`,context)
  return {context,message,events,errors,...actions}
}

test('editing a saved image reads its bytes before rewind deletes the original and keeps a sendable draft',async t=>{
  const f=await fixture(t);await f.editMessage(f.message)
  assert.deepEqual(f.events,['read-image','rewind'])
  assert.equal(f.context.text.value,'修改后的说明')
  assert.equal(f.context.images.value[0],`data:image/png;base64,${png.toString('base64')}`)
})

test('rewriting an image reply also preserves the image before deleting history',async t=>{
  const f=await fixture(t);await f.rewrite(1)
  assert.deepEqual(f.events,['read-image','rewind','send'])
  assert.equal(f.context.images.value[0],`data:image/png;base64,${png.toString('base64')}`)
})

test('an expired or missing image does not rewind or overwrite an existing draft',async t=>{
  const f=await fixture(t,{unavailable:true});f.context.text.value='未发送的草稿'
  await f.editMessage(f.message)
  assert.deepEqual(f.events,['read-image'])
  assert.equal(f.context.text.value,'未发送的草稿')
  assert.match(f.errors[0],/历史尚未修改/)
  assert.equal(f.context.historyBusy.value,false)
})

test('image replay never follows arbitrary remote addresses or redirects',async()=>{
  let calls=0
  await assert.rejects(prepareReplayImages(['https://other.test/photo.png'],{base:'https://story.test',fetchImage:async()=>{calls++}}),/历史尚未修改/)
  assert.equal(calls,0)
  const result=await prepareReplayImages(['data:image/png;base64,already-local'],{base:'https://story.test',fetchImage:async()=>{calls++}})
  assert.deepEqual(result,['data:image/png;base64,already-local']);assert.equal(calls,0)
})
