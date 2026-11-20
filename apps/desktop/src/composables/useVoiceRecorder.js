import { ref, onBeforeUnmount } from 'vue'

/** Owns microphone resources, including permission requests that resolve after release/unmount. */
export function useVoiceRecorder({ beforeStart, onAudio, onError }) {
  const isRecording = ref(false)
  let generation = 0
  let recorder = null
  let stream = null
  let disposed = false

  const stopRecording = () => {
    generation++
    if (recorder?.state === 'recording') recorder.stop()
    stream?.getTracks().forEach((track) => track.stop())
    stream = null
    isRecording.value = false
  }

  const cancelRecording = () => {
    if (recorder) recorder.onstop = null
    stopRecording()
  }

  const startRecording = async () => {
    if (isRecording.value || disposed) return
    const current = ++generation
    isRecording.value = true
    try {
      await beforeStart()
      if (current !== generation || disposed) return
      const acquired = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true } })
      if (current !== generation || disposed) {
        acquired.getTracks().forEach((track) => track.stop())
        return
      }
      stream = acquired
      const active = new MediaRecorder(acquired)
      recorder = active
      const chunks = []
      active.ondataavailable = (event) => {
        if (event.data.size) chunks.push(event.data)
      }
      active.onstop = () => {
        acquired.getTracks().forEach((track) => track.stop())
        if (!disposed && chunks.length)
          Promise.resolve().then(() => onAudio(new Blob(chunks, { type: active.mimeType }))).catch(onError)
      }
      active.onerror = (event) => {
        stopRecording()
        onError(event.error || new Error('录音失败'))
      }
      active.start()
    } catch (error) {
      if (current === generation) {
        stopRecording()
        onError(error)
      }
    }
  }

  onBeforeUnmount(() => {
    disposed = true
    stopRecording()
  })
  return { isRecording, startRecording, stopRecording, cancelRecording }
}
