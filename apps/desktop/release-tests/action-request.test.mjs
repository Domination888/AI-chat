import test from 'node:test'
import assert from 'node:assert/strict'
import { createActionRequest } from '../src/release/actionRequest.mjs'

test('cancelling a pending decision resolves without authorizing a mutation', async () => {
  let visible, writes = 0
  const actions = createActionRequest(value => { visible = value })
  const operation = (async () => { if (await actions.ask({ title: '回退' })) writes++ })()
  assert.equal(visible.title, '回退')
  actions.settle()
  await operation
  assert.equal(writes, 0)
  assert.equal(visible, null)
})

test('edited content is returned exactly once after explicit submission', async () => {
  const actions = createActionRequest(() => {})
  const result = actions.ask({ input: true })
  actions.settle('新的台词\n保留换行')
  actions.settle(null)
  assert.equal(await result, '新的台词\n保留换行')
})

test('opening another decision cancels the previous operation', async () => {
  let visible
  const actions = createActionRequest(value => { visible = value })
  const first = actions.ask({ title: '编辑' })
  const second = actions.ask({ title: '回退' })
  assert.equal(await first, null)
  assert.equal(visible.title, '回退')
  actions.settle(true)
  assert.equal(await second, true)
})
