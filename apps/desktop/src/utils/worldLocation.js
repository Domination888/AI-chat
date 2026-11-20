import { apiFetch } from './api.js'

const DEFAULT_TIMEOUT_MS = 12000

const responseError = async (response, fallback) => {
  const body = await response.json().catch(() => ({}))
  const error = new Error(body.message || body.error || `${fallback}（HTTP ${response.status}）`)
  error.kind = 'backend'
  error.status = response.status
  return error
}

export const locationSourceLabel = (source) => ({
  device: '设备定位（系统定位）',
  ip: '公网 IP 粗定位',
  manual: '手动城市',
  unset: '未定位'
}[source] || '未定位')

export const describeDeviceLocationError = (error, environment = false) => {
  // Legacy callers pass a macOS boolean; the release bridge supplies the actual
  // platform so Windows/Linux desktop failures are not labelled as macOS.
  const platform = typeof environment === 'string' ? environment : environment ? 'darwin' : 'browser'
  if (error?.kind === 'unsupported') {
    return '当前环境不支持设备定位'
  }
  if (error?.kind === 'backend') {
    return `设备坐标已取得，但后端未能保存：${error.message}`
  }
  if (error?.code === 1) {
    if (platform === 'darwin') return 'macOS 未授权位置访问，请在“系统设置 → 隐私与安全性 → 定位服务”中允许幕间（开发环境显示 Electron）'
    if (platform === 'win32') return 'Windows 未授权位置访问，请检查系统位置服务与桌面应用的位置权限'
    if (platform === 'linux') return '系统未授权位置访问，请检查桌面环境的定位服务与应用权限'
    return '浏览器未授权位置访问，请在地址栏的网站权限中允许定位'
  }
  if (error?.code === 3 || error?.kind === 'timeout') {
    if (platform === 'darwin') return '设备定位超时，请确认 macOS 定位服务已开启后重试'
    if (platform === 'win32') return '设备定位超时，请确认 Windows 位置服务已开启后重试'
    if (platform === 'linux') return '设备定位超时，请检查桌面环境的定位服务后重试'
    return '设备定位超时，请检查系统定位服务和网站权限后重试'
  }
  if (error?.code === 2) {
    return '系统暂时无法取得设备位置，请检查定位服务或网络'
  }
  return `设备定位失败：${error?.message || '未知错误'}`
}

export const requestDevicePosition = ({ timeout = DEFAULT_TIMEOUT_MS, maximumAge = 0, signal } = {}) => {
  if (signal?.aborted) return Promise.reject(new DOMException('定位已取消', 'AbortError'))
  if (!navigator.geolocation) {
    const error = new Error('Geolocation API unavailable')
    error.kind = 'unsupported'
    return Promise.reject(error)
  }

  // Electron/macOS 的网络定位后端曾出现不触发 API timeout 的永久挂起。
  // watchPosition 可以在我们自己的截止时间到达时被显式取消。
  return new Promise((resolve, reject) => {
    let settled = false
    let watchId
    const finish = (callback, value) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      signal?.removeEventListener('abort', onAbort)
      if (watchId !== undefined) navigator.geolocation.clearWatch(watchId)
      callback(value)
    }
    const onAbort = () => finish(reject, new DOMException('定位已取消', 'AbortError'))
    signal?.addEventListener('abort', onAbort, { once: true })
    const timer = setTimeout(() => {
      const error = new Error('设备定位超时')
      error.code = 3
      error.kind = 'timeout'
      finish(reject, error)
    }, timeout)
    watchId = navigator.geolocation.watchPosition(
      (position) => finish(resolve, position),
      (error) => finish(reject, error),
      { enableHighAccuracy: false, timeout, maximumAge }
    )
  })
}

const saveDevicePosition = async (position, timezone, signal) => {
  const latitude = Number(position.coords.latitude.toFixed(4))
  const longitude = Number(position.coords.longitude.toFixed(4))
  const response = await apiFetch('/api/world-context/location', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ latitude, longitude, timezone, source: 'device' }),
    signal
  })
  if (!response.ok) throw await responseError(response, '保存设备位置失败')
  return response.json()
}

const locateByIp = async (signal) => {
  const response = await apiFetch('/api/world-context/location/ip', { method: 'POST', signal })
  if (!response.ok) throw await responseError(response, 'IP 粗定位不可用')
  return response.json()
}

/** 优先设备定位；任何权限、超时或保存失败都会降级到后端 IP 粗定位。 */
export const locateWorldContext = async ({ timeout = DEFAULT_TIMEOUT_MS, maximumAge = 0, timezone, allowIpFallback = true, signal } = {}) => {
  const resolvedTimezone = timezone || Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai'
  let deviceError
  try {
    const position = await requestDevicePosition({ timeout, maximumAge, signal })
    const snapshot = await saveDevicePosition(position, resolvedTimezone, signal)
    return { snapshot, source: snapshot.locationSource || 'device', degraded: false, deviceError: null }
  } catch (error) {
    if (error?.name === 'AbortError') throw error
    deviceError = error
  }

  if (!allowIpFallback) throw deviceError

  try {
    const snapshot = await locateByIp(signal)
    return { snapshot, source: snapshot.locationSource || 'ip', degraded: true, deviceError }
  } catch (ipError) {
    const error = new Error('设备定位与 IP 粗定位均不可用')
    error.kind = 'all-unavailable'
    error.deviceError = deviceError
    error.ipError = ipError
    throw error
  }
}
