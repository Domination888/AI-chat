let pendingPermission = null

/** Ask for microphone access on launch, then release the device until recording starts. */
export function prepareMicrophone() {
  if (pendingPermission) return pendingPermission
  pendingPermission = (async () => {
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('当前环境不支持麦克风录音')
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    stream.getTracks().forEach(track => track.stop())
  })().finally(() => { pendingPermission = null })
  return pendingPermission
}
