/** Incremental SSE decoder. Transport framing is independent of Vue and UI state. */
export async function* readSse(body, signal) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (!signal?.aborted) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      if (buffer.length > 8 * 1024 * 1024) throw new Error('回复数据帧过大，请重试')
      for (const frame of frames) {
        if (signal?.aborted) return
        let event = 'message'
        const data = []
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
        }
        if (data.length) {
          let payload
          try {
            payload = JSON.parse(data.join('\n'))
          } catch {
            throw new Error('回复数据损坏，请重试')
          }
          yield { event, payload }
        }
      }
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
