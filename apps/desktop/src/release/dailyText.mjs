// Produce readable text only. Callers render with Vue interpolation, never v-html.
// Source links remain separate controls; remote images are not downloaded.
export function dailyText(value) {
  return String(value ?? '')
    .replace(/!\[([^\]]*)\]\((?:[^()]|\([^()]*\))*\)/g, (_, alt) => alt.trim() ? `（图片：${alt.trim()}）` : '')
    .replace(/!\[([^\]]*)\]\[[^\]]*\]/g, (_, alt) => alt.trim() ? `（图片：${alt.trim()}）` : '')
    .replace(/\[([^\]]+)\]\((?:[^()]|\([^()]*\))*\)/g, '$1')
    .replace(/^\s*\[[^\]]+\]:\s*https?:\/\/\S+.*$/gm, '')
    .replace(/^\s*```[^\n]*\n?/gm, '')
    .replace(/`+([^`]+)`+/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/^\s*#{1,6}\s+/gm, '')
    .replace(/https?:\/\/[^\s<>）。，；]+/g, url => {
      try { return `（链接：${new URL(url).hostname}）` } catch { return '（链接见原文）' }
    })
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}
