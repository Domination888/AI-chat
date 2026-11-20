export function formatRecordedTime(value, timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone) {
  if (value == null || value === '') return ''
  // Old imports without an offset are ambiguous; keep that uncertainty visible.
  if (typeof value === 'string' && (!/[T ]\d{2}:\d{2}/.test(value) || !/(?:Z|[+-]\d{2}(?::?\d{2})?)$/i.test(value))) return `${value}（未注明时区）`
  // PostgreSQL also emits SQL timestamps with microseconds and a short +08
  // offset. Normalize that explicit offset; never assume UTC for old local dates.
  const normalized = typeof value === 'string' ? value.trim().replace(' ', 'T')
    .replace(/([+-]\d{2})$/, '$1:00')
    .replace(/([+-]\d{2})(\d{2})$/, '$1:$2')
    .replace(/(\.\d{3})\d+(?=Z$|[+-]\d{2}:\d{2}$)/i, '$1') : value
  const date = new Date(normalized)
  if (!Number.isFinite(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(date) + `（${timeZone}）`
}
