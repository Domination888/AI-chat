<template>
  <div class="live2d-root">
    <canvas ref="canvasRef" class="live2d-canvas"
            @mousedown="onCanvasMouseDown"></canvas>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { live2dController } from '../live2d/live2d-controller.js'
import { allLive2dMotions, clickLive2dMotions } from '../live2d/live2d-options.js'
import * as PIXI from 'pixi.js'
import { Live2DModel, config } from 'pixi-live2d-display-lipsyncpatch/cubism4'

window.PIXI = PIXI
config.sound = false

const canvasRef = ref(null)
const scaleValue = ref(1.0)

let app = null
let model = null
let baseScale = 1.0
let expressionTimer = null
let resizeFrame = null
let lastIgnoreMouse = null
let lastMouseProbeAt = 0

const MODEL_PADDING = 36
const MIN_OVERLAY_WIDTH = 96
const MIN_OVERLAY_HEIGHT = 140
const ALPHA_HIT_THRESHOLD = 12
const MOUSE_PROBE_INTERVAL = 24

const setMousePassthrough = (ignore) => {
  if (lastIgnoreMouse === ignore) return
  lastIgnoreMouse = ignore
  if (window.live2dAPI?.setIgnoreMouseEvents) {
    window.live2dAPI.setIgnoreMouseEvents(ignore)
  }
}

const isInteractiveControlPoint = (x, y) => {
  const el = document.elementFromPoint(x, y)
  return Boolean(el?.closest?.('[data-live2d-control]'))
}

const getCanvasAlphaAt = (clientX, clientY) => {
  if (!app || !canvasRef.value) return 0
  const renderer = app.renderer
  const gl = renderer?.gl
  if (!gl) return 0

  const canvas = canvasRef.value
  const rect = canvas.getBoundingClientRect()
  if (!rect.width || !rect.height) return 0

  const x = Math.floor(((clientX - rect.left) / rect.width) * canvas.width)
  const y = Math.floor(((clientY - rect.top) / rect.height) * canvas.height)
  if (x < 0 || x >= canvas.width || y < 0 || y >= canvas.height) return 0

  const pixel = new Uint8Array(4)
  try {
    gl.readPixels(x, canvas.height - y - 1, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel)
    return pixel[3]
  } catch (err) {
    console.warn('[Live2DOverlay] alpha probe failed:', err)
    return 0
  }
}

const updateMousePassthrough = (e, force = false) => {
  if (_downScreen) {
    setMousePassthrough(false)
    return
  }
  if (force || isInteractiveControlPoint(e.clientX, e.clientY)) {
    setMousePassthrough(false)
    return
  }

  const now = performance.now()
  if (now - lastMouseProbeAt < MOUSE_PROBE_INTERVAL) return
  lastMouseProbeAt = now

  const alpha = getCanvasAlphaAt(e.clientX, e.clientY)
  setMousePassthrough(alpha <= ALPHA_HIT_THRESHOLD)
}

const onWindowMouseLeave = () => {
  setMousePassthrough(true)
}

const resizeRenderer = (width, height) => {
  if (!app || !model) return
  app.renderer.resize(width, height)
  model.x = app.screen.width / 2
  model.y = app.screen.height / 2
}

const fitWindowToModel = async () => {
  if (!model || !app || !window.live2dAPI?.getBounds || !window.live2dAPI?.setBounds) return

  const currentBounds = await window.live2dAPI.getBounds()
  if (!currentBounds) return

  const nextWidth = Math.max(MIN_OVERLAY_WIDTH, Math.ceil(model.width + MODEL_PADDING * 2))
  const nextHeight = Math.max(MIN_OVERLAY_HEIGHT, Math.ceil(model.height + MODEL_PADDING * 2))
  const centerX = currentBounds.x + model.x
  const centerY = currentBounds.y + model.y
  const nextBounds = {
    x: centerX - nextWidth / 2,
    y: centerY - nextHeight / 2,
    width: nextWidth,
    height: nextHeight,
  }

  resizeRenderer(nextWidth, nextHeight)
  window.live2dAPI.setBounds(nextBounds)
}

const scheduleFitWindowToModel = () => {
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => {
    resizeFrame = null
    fitWindowToModel()
  })
}

// ============================================================
// 点击互动 + 窗口拖动：
// mousedown 在 canvas 上监听，mousemove/mouseup 挂到 document。
// 拖动判定：鼠标移动超过 5px 才算拖动，否则松开视为点击互动。
// 拖动方式：delta 模式 — 记录 mousedown 时的窗口位置 + 鼠标屏幕坐标，
//   mousemove 时将窗口移到 (downWinPos + delta)，避免坐标体系混用。
// ============================================================

let _downScreen = null    // { x, y } mousedown 时鼠标的屏幕坐标
let _downClient = null    // { x, y } mousedown 时鼠标的视口坐标（用于阈值判定）
let _isDragging = false
let _mouseDownEvent = null  // 保存 mousedown 事件用于 doHitTest
const _DRAG_THRESHOLD = 5   // 拖动判定阈值（px），移动超过此距离才算拖动

const _onDocMouseMove = (e) => {
  if (!_downScreen) return
  if (!_isDragging) {
    const dx = e.clientX - _downClient.x
    const dy = e.clientY - _downClient.y
    if (Math.sqrt(dx * dx + dy * dy) < _DRAG_THRESHOLD) return
    _isDragging = true
  }
  if (window.live2dAPI) {
    window.live2dAPI.moveWindow(_downWinPos.x + (e.screenX - _downScreen.x),
                                _downWinPos.y + (e.screenY - _downScreen.y))
  }
}

const _onDocMouseUp = (e) => {
  document.removeEventListener('mousemove', _onDocMouseMove)
  document.removeEventListener('mouseup', _onDocMouseUp)

  const wasDragging = _isDragging
  const downEvent = _mouseDownEvent
  _downScreen = null
  _downClient = null
  _downWinPos = null
  _mouseDownEvent = null
  _isDragging = false

  // 非拖动 → 视为点击（用 mousedown 的事件坐标做 hitTest）
  if (!wasDragging && downEvent) {
    doHitTest(downEvent)
  }
}

let _downWinPos = null  // mousedown 时窗口的屏幕位置

const onCanvasMouseDown = (e) => {
  setMousePassthrough(false)
  _downScreen = { x: e.screenX, y: e.screenY }
  _downClient = { x: e.clientX, y: e.clientY }
  _mouseDownEvent = e
  _isDragging = false

  // 窗口位置 = 鼠标屏幕位置 - 鼠标在视口中的位置（对无边框透明窗口准确）
  _downWinPos = { x: e.screenX - e.clientX, y: e.screenY - e.clientY }

  document.addEventListener('mousemove', _onDocMouseMove)
  document.addEventListener('mouseup', _onDocMouseUp)
}

// 点击互动：bbox 检测（PIXI hitTest 在 drag 区域不可靠）
const doHitTest = (e) => {
  if (!model || !app) return
  const rect = canvasRef.value.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top

  // bbox 矩形检测：点在模型范围内就触发互动
  if (model) {
    const hw = model.width / 2
    const hh = model.height / 2
    if (x >= model.x - hw && x <= model.x + hw && y >= model.y - hh && y <= model.y + hh) {
      const m = clickLive2dMotions[Math.floor(Math.random() * clickLive2dMotions.length)]
      playMotion(m)
      console.log('[Live2D] click hit →', m.name)
      // 通知主窗口用户点击了模型（触发主动说话）
      if (window.live2dAPI) {
        window.live2dAPI.sendInteract()
      }
    }
  }
}

// ============================================================
// 动作 / 表情 / 缩放
// ============================================================

const playMotion = (m) => {
  if (!model) return
  // 只触发动作，不触发声音（避免与 TTS 语音冲突）
  config.sound = false
  try { model.motion(m.group, m.index) } catch (e) { console.warn(e) }
}

const playExpression = (name) => {
  if (!model) return
  try {
    const expressionManager = model.internalModel?.motionManager?.expressionManager
    if (expressionManager?.stopAllExpressions) {
      expressionManager.stopAllExpressions()
    }
    model.expression(name)
    // 3秒后自动重置表情回默认
    if (expressionTimer) clearTimeout(expressionTimer)
    expressionTimer = setTimeout(() => { resetExpression() }, 3000)
  } catch (e) { console.warn(e) }
}

/**
 * 重置表情到默认状态
 *
 * 关键机制：pixi-live2d-display 的表情是通过 CubismExpressionMotion 实现的，
 * 表情本质上是一个"无时限的持续 motion"，每帧 update 会用 Add 模式对参数叠加偏移。
 * 因此：
 *   - 只重置 coreModel.parameters.values 无效（下一帧表情 motion 又写回偏移）
 *   - 只设 expressionManager.currentExpression = null 无效（没停止 queue 中的 motion）
 *
 * 正确做法：调用 expressionManager.resetExpression()，它内部会调用
 * _setExpression(defaultExpression)，把一个空表情推入 motion queue 取代当前表情。
 * 同时把 currentExpression 重置为 defaultExpression，防止 restoreExpression() 恢复旧表情。
 */
const resetExpression = () => {
  if (!model) return
  if (expressionTimer) { clearTimeout(expressionTimer); expressionTimer = null }
  try {
    const internalModel = model.internalModel
    if (internalModel) {
      const expressionManager = internalModel.motionManager?.expressionManager
      if (expressionManager) {
        // Clear any queued expressions so the reset actually takes effect.
        if (expressionManager.stopAllExpressions) {
          expressionManager.stopAllExpressions()
        }
        // 1. 调用官方 API：推入空表情 motion 取代当前表情
        expressionManager.resetExpression()
        // 2. 重置 currentExpression 为 defaultExpression，防止 restoreExpression() 恢复旧表情
        expressionManager.currentExpression = expressionManager.defaultExpression
        expressionManager.reserveExpressionIndex = -1
      }

      // 3. 重置 coreModel 眼睛参数到默认值（确保眼睛睁开）
      const coreModel = internalModel.coreModel
      if (coreModel) {
        const eyeLOpenIdx = coreModel.getParameterIndex('ParamEyeLOpen')
        const eyeROpenIdx = coreModel.getParameterIndex('ParamEyeROpen')
        if (eyeLOpenIdx >= 0) coreModel.setParameterValueByIndex(eyeLOpenIdx, 1.0)
        if (eyeROpenIdx >= 0) coreModel.setParameterValueByIndex(eyeROpenIdx, 1.0)
      }
    }
  } catch (e) {
    console.warn('[Live2DOverlay] resetExpression failed:', e)
    try { model.expression() } catch (_) {}
  }
}

const onScaleChange = () => {
  if (!model) return
  model.scale.set(baseScale * scaleValue.value)
  scheduleFitWindowToModel()
}

const setScale = (scale) => {
  const nextScale = Number(scale)
  if (!Number.isFinite(nextScale)) return
  scaleValue.value = Math.min(2.0, Math.max(0.3, nextScale))
  onScaleChange()
}

// ============================================================
// 初始化
// ============================================================
onMounted(async () => {
  try {
    app = new PIXI.Application({
      view: canvasRef.value,
      backgroundAlpha: 0,
      autoDensity: true,
      preserveDrawingBuffer: true,
      resolution: window.devicePixelRatio || 1,
      width: 350,
      height: 500,
    })

    if (!window.Live2DCubismCore) {
      console.warn('[Live2DOverlay] Cubism Core SDK not loaded')
    }

    model = await Live2DModel.from(`${import.meta.env.BASE_URL}live2d/shu/黍.model3.json`, {
      autoInteract: false,
      autoUpdate: true,
    })

    model.anchor.set(0.5, 0.5)
    model.x = app.screen.width / 2
    model.y = app.screen.height / 2

    const scaleX = app.screen.width / model.width
    const scaleY = app.screen.height / model.height
    baseScale = Math.min(scaleX, scaleY) * 0.9
    model.scale.set(baseScale)

    app.stage.addChild(model)
    live2dController.setModel(model)
    scheduleFitWindowToModel()
    setMousePassthrough(true)
    window.addEventListener('mousemove', updateMousePassthrough)
    window.addEventListener('mouseleave', onWindowMouseLeave)

    // 修复 idle 动画循环播放问题：
    // 默认 idleMotionFadingDuration=2000ms（2s），导致每次 idle 重新调度时
    // 有 2s 的 fadeIn+fadeOut 淡化过渡，把 1.6s 的待机动画吃掉大半，
    // 视觉上就是"只循环了一部分"。缩短到 300ms 让衔接无缝。
    config.idleMotionFadingDuration = 300
    config.motionFadingDuration = 300

    // 补修已预加载的 Idle 动作（createMotion 在预加载时用的是旧值 2000ms）
    const motionManager = model.internalModel?.motionManager
    if (motionManager) {
      const fixLoadedMotions = () => {
        const groups = motionManager.motionGroups
        if (!groups) return
        const idleKey = motionManager.groups?.idle
        if (idleKey && groups[idleKey]) {
          for (const motion of groups[idleKey]) {
            if (motion) {
              motion.setFadeInTime(0.3)
              motion.setFadeOutTime(0.3)
            }
          }
        }
      }
      fixLoadedMotions()
      setTimeout(fixLoadedMotions, 500)
    }

    // 播放入场动作
    playMotion({ group: 'Entry', index: 0 })

    // IPC 控制指令
    if (window.live2dAPI) {
      window.live2dAPI.onAction((action, data) => {
        switch (action) {
          case 'emotion': {
            const emotion = typeof data === 'string' ? data : data?.emotion
            live2dController.triggerEmotion(emotion)
            break
          }
          case 'motion': {
            const motion = typeof data === 'string'
              ? allLive2dMotions.find(item => item.name === data)
              : data
            if (motion) playMotion(motion)
            break
          }
          case 'expression': {
            const expression = typeof data === 'string' ? data : data?.name
            if (expression) playExpression(expression)
            break
          }
          case 'scale': setScale(typeof data === 'number' ? data : data?.scale); break
          case 'reset': resetExpression(); break
          case 'end': live2dController.onConversationEnd(); break
          case 'stopLipSync': live2dController.stopLipSync(); break
        }
      })
    }

    console.log('[Live2DOverlay] model loaded successfully')
  } catch (e) {
    console.error('[Live2DOverlay] failed to initialize:', e)
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('mousemove', _onDocMouseMove)
  document.removeEventListener('mouseup', _onDocMouseUp)
  window.removeEventListener('mousemove', updateMousePassthrough)
  window.removeEventListener('mouseleave', onWindowMouseLeave)
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  setMousePassthrough(false)
  if (expressionTimer) clearTimeout(expressionTimer)
  live2dController.clearModel()
  if (model) { app?.stage.removeChild(model); model = null }
  if (app) { app.destroy(true); app = null }
})
</script>

<style scoped>
.live2d-root {
  width: 100vw;
  height: 100vh;
  position: relative;
  overflow: hidden;
}

.live2d-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  z-index: 1;
}
</style>
