<template>
  <!-- 主窗口中不渲染模型，Live2D 在独立子窗口中渲染 -->
  <!-- 此组件仅作为 IPC 桥接层，通过 electronAPI.live2dControl 控制子窗口 -->
</template>

<script setup>
/**
 * Live2D IPC 桥接组件
 *
 * 主窗口中不再渲染 Live2D 模型（模型在 Electron 透明子窗口中渲染）。
 * 此组件将 live2dController 的调用转发到子窗口：
 *   - Electron 环境：通过 window.electronAPI.live2dControl() IPC 转发
 *   - 浏览器环境（开发/非 Electron）：降级为空操作
 */

import { live2dController } from '../live2d/live2d-controller.js'

// 覆写 live2dController 的方法，使其通过 IPC 转发到子窗口
const isElectron = !!window.electronAPI

live2dController.triggerEmotion = (emotion, options) => {
  if (isElectron) {
    window.electronAPI.live2dControl('emotion', { emotion, ...options })
  }
}

live2dController.setMotionSoundEnabled = (enabled) => {
  live2dController._motionSoundEnabled = !!enabled
}

live2dController.resetExpression = () => {
  if (isElectron) {
    window.electronAPI.live2dControl('reset', null)
  }
}

live2dController.onConversationEnd = () => {
  if (isElectron) {
    window.electronAPI.live2dControl('end', null)
  }
  live2dController._motionSoundEnabled = false
}

live2dController.startLipSync = () => {
  if (isElectron) {
    window.electronAPI.live2dControl('startLipSync', null)
  }
}

live2dController.stopLipSync = () => {
  if (isElectron) {
    window.electronAPI.live2dControl('stopLipSync', null)
  }
}
</script>
