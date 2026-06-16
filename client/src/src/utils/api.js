/** 生产 Electron 走绝对 URL；开发走 Vite proxy */
let cachedBase = import.meta.env.VITE_API_BASE || ''

export async function initApiBase() {
  if (window.electronAPI?.getApiBase) {
    try {
      cachedBase = await window.electronAPI.getApiBase()
    } catch (e) {
      console.warn('getApiBase failed', e)
    }
  }
}

export function getApiBase() {
  return cachedBase
}

export function apiUrl(path) {
  const p = path.startsWith('/') ? path : `/${path}`
  return `${cachedBase}${p}`
}

export async function apiFetch(path, init) {
  if (window.electronAPI?.getApiBase) {
    await initApiBase()
  }
  const url = apiUrl(path)
  try {
    return await fetch(url, init)
  } catch (e) {
    const hint = url ? ` (${url})` : ''
    throw new Error(e?.message === 'Failed to fetch' ? `无法连接后端${hint}` : (e?.message || '请求失败'))
  }
}
