let base = import.meta.env?.VITE_API_BASE || ''
let access = ''
let memoryRefresh = ''
let refreshing = null
let sessionInvalidated = false
const expiredListeners = new Set()
export class SessionExpiredError extends Error {
  constructor() { super('登录状态已失效，请重新登录。'); this.name = 'SessionExpiredError'; this.code = 'SESSION_EXPIRED' }
}
export function isSessionExpired(error) { return error?.code === 'SESSION_EXPIRED' }
export function onSessionExpired(listener) { expiredListeners.add(listener); return () => expiredListeners.delete(listener) }
function expireSession() {
  access = ''; memoryRefresh = ''; runtimeInfo.sessionExpired = true
  if (!sessionInvalidated) {
    sessionInvalidated = true
    for (const listener of expiredListeners) listener()
  }
  return new SessionExpiredError()
}
export let runtimeInfo = {}
export const distribution = import.meta.env?.VITE_DISTRIBUTION || 'cloud'
export function apiBase() { return base }
export function url(path) { return /^https?:/.test(path) ? path : base + (path.startsWith('/') ? path : '/' + path) }
export function normalizeServer(value, allowLocal = false) {
  const parsed = new URL(value)
  if (parsed.username || parsed.password || parsed.search || parsed.hash || parsed.pathname !== '/') throw new Error('服务地址只填写域名，例如 https://story.example.com')
  if (parsed.protocol !== 'https:' && !(allowLocal && parsed.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(parsed.hostname))) throw new Error('服务地址必须使用 HTTPS')
  return parsed.origin
}
const serverStorageKey = `story-server-${distribution}`
function allowLocalServer() {
  return !import.meta.env?.PROD || (distribution === 'admin' && ['localhost', '127.0.0.1', '[::1]'].includes(window.location.hostname))
}
export async function connect(value) {
  base = normalizeServer(value, allowLocalServer())
  access = ''; memoryRefresh = ''; sessionInvalidated = false
  await window.storyDesktop?.setServer(base)
  // Remember only the public service origin. Browser credentials stay in memory.
  if (!window.storyDesktop) {
    try { window.localStorage?.setItem(serverStorageKey, base) } catch { /* Private browsing can disable storage. */ }
  }
}
export async function initialize() {
  if (window.storyDesktop) {
    const config = await window.storyDesktop.bootstrap()
    runtimeInfo = config
    base = config.apiBase || base
    if (config.mode === 'offline') access = config.localToken
    else if (config.hasSession) {
      try { return await refresh() }
      catch { access = ''; memoryRefresh = ''; runtimeInfo.sessionExpired = true }
    }
  } else if (!base) {
    try {
      const saved = window.localStorage?.getItem(serverStorageKey)
      if (saved) base = normalizeServer(saved, allowLocalServer())
    } catch { /* A missing, unavailable or invalid preference must not prevent login. */ }
  }
  return null
}
export async function acceptSession(session) {
  sessionInvalidated = false; runtimeInfo.sessionExpired = false
  access = session.accessToken
  if (window.storyDesktop) await window.storyDesktop.saveSession(session.refreshToken)
  else memoryRefresh = session.refreshToken
  return session.user
}
export async function refresh() {
  if (!refreshing) refreshing = (async () => {
    const session = window.storyDesktop ? await window.storyDesktop.refreshSession() : await json('/api/v1/auth/refresh', { refreshToken: memoryRefresh }, 'POST', false)
    if (!session) throw new Error('请重新登录')
    access = session.accessToken
    if (!window.storyDesktop) memoryRefresh = session.refreshToken
    return session.user
  })().finally(() => { refreshing = null })
  return refreshing
}
export async function logout() {
  if (window.storyDesktop) await window.storyDesktop.logout()
  else if (memoryRefresh) await json('/api/v1/auth/logout', { refreshToken: memoryRefresh }, 'POST', false).catch(() => {})
  access = ''; memoryRefresh = ''
}
export async function request(path, init = {}, retry = true) {
  const headers = new Headers(init.headers)
  if (access) headers.set('Authorization', 'Bearer ' + access)
  const response = await fetch(url(path), { ...init, headers })
  if (response.status === 401 && !path.startsWith('/api/v1/auth/') && distribution !== 'offline') {
    if (!retry || sessionInvalidated) throw expireSession()
    try { await refresh() } catch { throw expireSession() }
    return request(path, init, false)
  }
  return response
}
export async function json(path, payload, method = 'GET', retry = true) {
  const response = await request(path, { method, ...(payload !== undefined ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) } : {}) }, retry)
  if (!response.ok) {
    const detail = await response.json().catch(() => ({}))
    throw new Error(detail.message || `操作失败（${response.status}）`)
  }
  const text = await response.text()
  return text ? JSON.parse(text) : null
}
export function imageUrls(encoded) {
  try { return JSON.parse(encoded || '[]').map(src => src.startsWith('/api/') ? url(src) : src) } catch { return [] }
}
