// Proactive messages have no user turn to rewind and replay, even after branching.
export function regenerationAction(messages, index) {
  const reply = messages[index]
  if (reply?.role !== 'ai') return null
  if (reply.completion?.incomplete && reply.completion.reason === 'length') return { kind: 'continue', label: '继续回复' }
  if (reply.isProactive) return { kind: 'proactive', label: '再次开场' }
  if (messages[index - 1]?.role !== 'user') return null
  return {
    kind: 'reply',
    label: ['failed', 'interrupted'].includes(reply.status) ? '重试' : '重写',
  }
}

// A paused reply can be continued without replacing its existing visible text.
export function manualContinuationAvailable(reply){return reply?.role==='ai'&&reply.status==='interrupted'&&!!reply.id&&!!reply.content?.trim()&&!(reply.completion?.incomplete&&reply.completion.reason==='length')}
