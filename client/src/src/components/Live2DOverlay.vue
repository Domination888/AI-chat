<template>
  <div class="live2d-root">
    <canvas ref="canvasRef" class="live2d-canvas"
            @mousedown="onCanvasMouseDown"></canvas>

    <!-- 动作面板（可折叠） -->
    <div class="motion-panel" v-if="showPanel" @mousedown.stop @click.stop>
      <div class="panel-header">
        <span>动作</span>
        <button @click="showPanel = false" class="btn-close">x</button>
      </div>
      <div class="panel-grid">
        <button v-for="m in allMotions" :key="m.group+m.index"
                @click="playMotion(m)" class="btn-motion">
          {{ m.name }}
        </button>
      </div>
      <div class="panel-header" style="margin-top:6px">
        <span>表情</span>
      </div>
      <div class="panel-grid">
        <button v-for="e in allExpressions" :key="e"
                @click="playExpression(e)" class="btn-motion">
          {{ e }}
        </button>
        <button @click="resetExpression" class="btn-motion btn-reset">重置</button>
      </div>
      <div class="panel-header" style="margin-top:6px">
        <span>大小</span>
      </div>
      <input type="range" min="0.3" max="2.0" step="0.05"
             v-model.number="scaleValue"
             @input="onScaleChange"
             class="scale-slider" />
    </div>

    <!-- 面板开关按钮 -->
    <button v-if="!showPanel" @click.stop="showPanel = true"
            @mousedown.stop class="btn-toggle">☰</button>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { live2dController } from '../live2d/live2d-controller.js'
import * as PIXI from 'pixi.js'
import { Live2DModel, config } from 'pixi-live2d-display-lipsyncpatch/cubism4'

window.PIXI = PIXI

const canvasRef = ref(null)
const showPanel = ref(false)
const scaleValue = ref(1.0)

let app = null
let model = null
let baseScale = 1.0
let expressionTimer = null

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
      const m = clickMotions[Math.floor(Math.random() * clickMotions.length)]
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

// 所有动作（含待机和入场）
const allMotions = [
  { name: '待机', group: 'Idle', index: 0 },
  { name: '入场', group: 'Entry', index: 0 },
  { name: '种到土里', group: 'Tap#4', index: 0 },
  { name: '种到地里', group: 'Tap#4', index: 1 },
  { name: '谷种入田野', group: 'Tap#4', index: 2 },
  { name: '钓鱼', group: 'Tap#4', index: 3 },
  { name: '掐腰', group: 'Tap#4', index: 4 },
  { name: '有大麟', group: 'Tap#4', index: 5 },
  { name: '生气', group: 'Tap#4', index: 6 },
  { name: '晃手', group: 'Tap#4', index: 7 },
  { name: '闭眼', group: 'Tap#4', index: 8 },
]
const allExpressions = ['闭眼', '皱眉', '闭一只眼', '震惊']
const clickMotions = allMotions.filter(m =>
  ['晃手', '种到地里', '谷种入田野', '钓鱼'].includes(m.name)
)

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
      resolution: window.devicePixelRatio || 1,
      width: 350,
      height: 500,
    })

    if (!window.Live2DCubismCore) {
      console.warn('[Live2DOverlay] Cubism Core SDK not loaded')
    }

    model = await Live2DModel.from('/live2d/shu/黍.model3.json', {
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
          case 'emotion': live2dController.triggerEmotion(data); break
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

/* 动作面板 */
.motion-panel {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 10;
  background: rgba(0,0,0,0.65);
  border-radius: 8px;
  padding: 6px;
  color: #fff;
  font-size: 12px;
  max-height: 90vh;
  overflow-y: auto;
  -webkit-app-region: no-drag;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: bold;
  margin-bottom: 4px;
}
.btn-close {
  background: none;
  border: none;
  color: #aaa;
  cursor: pointer;
  font-size: 14px;
  padding: 0 4px;
}
.btn-close:hover { color: #fff; }
.panel-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 4px;
}
.btn-motion {
  background: rgba(255,255,255,0.15);
  border: 1px solid rgba(255,255,255,0.2);
  color: #fff;
  border-radius: 4px;
  padding: 4px 2px;
  font-size: 11px;
  cursor: pointer;
  transition: background 0.15s;
}
.btn-motion:hover { background: rgba(255,255,255,0.3); }
.btn-reset { background: rgba(255,200,0,0.3); }
.scale-slider {
  width: 100%;
  margin-top: 4px;
  cursor: pointer;
}
.btn-toggle {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 10;
  background: rgba(0,0,0,0.5);
  color: #fff;
  border: none;
  border-radius: 4px;
  width: 28px;
  height: 28px;
  font-size: 16px;
  cursor: pointer;
  -webkit-app-region: no-drag;
}
.btn-toggle:hover { background: rgba(0,0,0,0.7); }
</style>