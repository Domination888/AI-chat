// Resolve signed history images before rewinding: that operation may remove
// the last reference to the original attachment and delete its stored bytes.
export async function prepareReplayImages(images, { base, fetchImage = fetch } = {}) {
  const origin = base ? new URL(base).origin : null
  return Promise.all(images.map(async source => {
    if (source.startsWith('data:image/')) return source
    const url = new URL(source)
    if (!origin || url.origin !== origin || !/^\/api\/attachments\/[^/]+\/content$/.test(url.pathname)) {
      throw new Error('原照片地址不可用，历史尚未修改。请重新打开篇章后再试。')
    }
    try {
      const response = await fetchImage(url.href, { redirect: 'error', signal: AbortSignal.timeout(15000) })
      if (!response.ok) throw new Error('Image unavailable')
      const blob = await response.blob()
      const type = blob.type.split(';')[0].toLowerCase()
      if (!['image/png', 'image/jpeg', 'image/webp'].includes(type) || !blob.size || blob.size > 5 * 1024 * 1024) throw new Error('Invalid image')
      const bytes = new Uint8Array(await blob.arrayBuffer())
      let binary = ''
      for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192))
      return `data:${type};base64,${btoa(binary)}`
    } catch {
      throw new Error('原照片读取失败，历史尚未修改。请重新打开篇章后再试。')
    }
  }))
}
