import test from 'node:test'
import assert from 'node:assert/strict'
import { regenerationAction } from '../src/release/messageActions.mjs'

test('a proactive message without a turn id can invite another opening', () => {
  const messages = [{ role: 'ai', content: '你好' }, { id: 12, role: 'ai', isProactive: true, content: '今天如何？' }]
  assert.deepEqual(regenerationAction(messages, 1), { kind: 'proactive', label: '再次开场' })
})

test('proactive metadata takes precedence over an adjacent user message after branching', () => {
  assert.deepEqual(regenerationAction([{ role: 'user' }, { role: 'ai', isProactive: true, status: 'failed' }], 1), { kind: 'proactive', label: '再次开场' })
})

test('a preset greeting does not offer an operation that needs a user prompt', () => {
  assert.equal(regenerationAction([{ role: 'ai', content: '欢迎来到书店' }], 0), null)
})

test('ordinary failed and completed replies preserve retry and rewrite actions', () => {
  assert.deepEqual(regenerationAction([{ role: 'user' }, { role: 'ai', status: 'failed' }], 1), { kind: 'reply', label: '重试' })
  assert.deepEqual(regenerationAction([{ role: 'user' }, { role: 'ai', status: 'complete' }], 1), { kind: 'reply', label: '重写' })
})

test('length-limited replies offer continuation, including timed and proactive replies', () => {
  for(const isProactive of [false,true])assert.deepEqual(regenerationAction([{role:'user'},{role:'ai',isProactive,status:'interrupted',completion:{reason:'length',incomplete:true}}],1),{kind:'continue',label:'继续回复'})
})
