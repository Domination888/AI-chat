import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readSse } from '../src/utils/sse.js'

function stream(text, size = 1, onCancel = () => {}) {
  const bytes = new TextEncoder().encode(text)
  let offset = 0
  return new ReadableStream({
    pull(controller) {
      if (offset === bytes.length) return controller.close()
      controller.enqueue(bytes.slice(offset, offset += size))
    }, cancel: onCancel
  })
}

test('SSE handles split UTF-8 characters, CRLF, comments and multiline JSON', async () => {
  const events = []
  for await (const event of readSse(stream(': heartbeat\r\n\r\nevent: text\r\ndata: {\r\ndata: "delta":"你来了。"}\r\n\r\nevent: done\ndata: {}\n\n'))) events.push(event)
  assert.deepEqual(events, [{event:'text', payload:{delta:'你来了。'}}, {event:'done', payload:{}}])
})
test('malformed events fail visibly instead of silently deleting part of a reply', async () => {
  await assert.rejects(async () => { for await (const _ of readSse(stream('data: invalid\n\n'))) {} }, /回复数据损坏/)
})
test('consumer cancellation releases transport and cannot deliver another event', async () => {
  let cancelled = false
  const signal = new AbortController()
  let count = 0
  for await (const _ of readSse(stream('data: {}\n\ndata: {}\n\n', 10, () => {cancelled = true}), signal.signal)) {
    count++; signal.abort()
  }
  assert.equal(count, 1)
  assert.equal(cancelled, true)
})
