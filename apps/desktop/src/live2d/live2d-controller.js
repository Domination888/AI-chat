/**
 * Live2D 控制器 — 核心驱动
 *
 * 职责：
 *   1. 情绪驱动：根据映射表触发表情 + 动作（动作音效始终静音）
 *   2. 口型同步：WebAudio Analyser 实时分析频率，驱动 ParamMouthOpenY
 *   3. 表情恢复：对话结束后恢复默认表情
 */

import { getEmotionMapping } from './emotion-mappings.js'

export class Live2DController {
  constructor() {
    /** @type {import('pixi-live2d-display').Live2DModel|null} */
    this.model = null
    this._lipSyncActive = false
    if(this._lipSyncModel&&this._lipSyncFrame)this._lipSyncModel.off('beforeModelUpdate',this._lipSyncFrame)
    this._lipSyncModel=null;this._lipSyncFrame=null
    this._lipSyncRAF = null
    this._lipSyncAnalyser = null
    this._lipSyncSource = null
    this._emotionResetTimer = null
    this._conversationEndTimer = null
    this._manualExpression = false
    /** 保留兼容旧调用；Live2D 表情/动作音效始终禁用 */
    this._motionSoundEnabled = false
  }

  /** @returns {boolean} */
  get motionSoundEnabled() {
    return this._motionSoundEnabled
  }

  /**
   * 兼容旧调用：Live2D 表情/动作音效始终禁用（表情、动作、口型同步不受影响）
   * @param {boolean} enabled
   */
  setMotionSoundEnabled(_enabled) {
    this._motionSoundEnabled = false
  }

  /**
   * 绑定已加载的 Live2D 模型实例
   * @param {import('pixi-live2d-display').Live2DModel} model
   */
  setModel(model) {
    this._cancelExpressionReset()
    this._manualExpression = false
    this.model = model
    console.log('[Live2DController] model bound')
  }

  /**
   * 解绑模型
   */
  clearModel() {
    this._cancelExpressionReset()
    this.stopLipSync()
    this.model = null
  }

  // ============================================================
  // 情绪驱动
  // ============================================================

  /**
   * 触发情绪（表情 + 动作同时触发）
   * @param {string} emotion 情绪名称，如 '开心'、'生气'
   * @param {object} [_options] 兼容旧调用；Live2D 动作音效始终静音
   */
  triggerEmotion(emotion, _options = {}) {
    if (!this.model) {
      console.warn('[Live2DController] no model, skip triggerEmotion:', emotion)
      return
    }
    const mapping = this.presentation?.emotions?.[emotion] || getEmotionMapping(emotion)
    if (!mapping) {
      console.warn('[Live2DController] unknown emotion:', emotion)
      return
    }

    console.log('[Live2DController] triggerEmotion:', emotion, mapping)

    // 新对话情绪取代上一次手动表情。
    this._manualExpression = false
    // 触发表情
    this.triggerExpression(mapping.expression)

    // 表情/动作触发始终静音，避免与 TTS 或系统音频重叠
    if (mapping.motion && Date.now() - (this.lastMotionAt || 0) > 10000) {
      this.triggerMotion(mapping.motion, true); this.lastMotionAt = Date.now()
    }

    // 设定自动恢复计时器：3s 后恢复默认表情
    this._scheduleEmotionReset()
  }

  /**
   * 触发表情
   * @param {string|null} expressionName 表情名称，null 表示不切换表情
   */
  async triggerExpression(expressionName, { manual = false } = {}) {
    if (!this.model || !expressionName) return false
    if (manual) {
      this._cancelExpressionReset()
      this._manualExpression = true
    }
    const model = this.model
    try {
      const manager = model.internalModel?.motionManager?.expressionManager
      const index = manager?.getExpressionIndex?.(expressionName)
      // The library rejects the current expression as a no-op. Do not stop its
      // queue first: that would leave the same expression selected but invisible.
      if (index >= 0 && manager.expressions?.[index] === manager.currentExpression) {
        manager.restoreExpression()
        return true
      }
      return (await model.expression(expressionName)) === true && this.model === model
    } catch (e) {
      console.warn('[Live2DController] expression failed:', expressionName, e)
      return false
    }
  }

  /**
   * 触发动作
   * @param {{group: string, index: number}} motionConfig
   * @param {boolean} [_muteSound=true] 兼容旧调用；Live2D 动作音效始终静音
   */
  async triggerMotion(motionConfig, _muteSound = true, priority) {
    if (!this.model || !motionConfig) return false
    const model = this.model
    try {
      const manager = model.internalModel?.motionManager
      if (priority === 3 && manager?.state?.currentGroup === motionConfig.group && manager.state.currentIndex === motionConfig.index) {
        manager.stopAllMotions()
      }
      return (await model.motion(motionConfig.group, motionConfig.index, priority)) === true && this.model === model
    } catch (e) {
      console.warn('[Live2DController] motion failed:', motionConfig, e)
      return false
    }
  }

  /**
   * 恢复默认表情
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
  resetExpression({ manual = false } = {}) {
    if (manual) {
      this._cancelExpressionReset()
      this._manualExpression = true
    }
    if (!this.model) return false
    try {
      const internalModel = this.model.internalModel
      if (internalModel) {
        const expressionManager = internalModel.motionManager?.expressionManager
        if (!expressionManager) return false
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
        return true
      }
      return false
    } catch (e) {
      console.warn('[Live2DController] resetExpression failed:', e)
      return false
    }
  }

  /**
   * 设定情绪自动恢复计时器
   */
  _cancelExpressionReset() {
    clearTimeout(this._emotionResetTimer)
    clearTimeout(this._conversationEndTimer)
    this._emotionResetTimer = null
    this._conversationEndTimer = null
  }

  _scheduleEmotionReset() {
    if (this._emotionResetTimer) {
      clearTimeout(this._emotionResetTimer)
    }
    this._emotionResetTimer = setTimeout(() => {
      this.resetExpression()
      this._emotionResetTimer = null
    }, 3000)
  }

  // ============================================================
  // 口型同步
  // ============================================================

  /**
   * 启动口型同步
   * 将 AudioContext 的 AnalyserNode 连接到 Live2D 模型的 ParamMouthOpenY 参数
   *
   * @param {AudioContext} audioCtx 全局 AudioContext
   * @param {AudioBufferSourceNode|MediaElementAudioSourceNode} audioSource 音频源节点
   */
  startLipSync(audioCtx, audioSource) {
    if (!this.model) return

    // 先停止之前的口型同步
    this.stopLipSync()

    try {
      // 创建 AnalyserNode
      const analyser = audioCtx.createAnalyser()
      analyser.fftSize = 256
      analyser.smoothingTimeConstant = 0.8

      // 连接音频源到分析器（不连接到 destination，避免重复输出）
      audioSource.connect(analyser)

      this._lipSyncAnalyser = analyser
      this._lipSyncSource = audioSource
      this._lipSyncActive = true

      // 开始动画循环
      this._lipSyncModel=this.model.internalModel
      this._lipSyncFrame=()=>this._lipSyncLoop()
      this._lipSyncModel.on('beforeModelUpdate',this._lipSyncFrame)
      console.log('[Live2DController] lipSync started')
    } catch (e) {
      console.warn('[Live2DController] startLipSync failed:', e)
    }
  }

  /**
   * 口型同步动画循环
   * 使用 WebAudio Analyser 实时分析音量，驱动 ParamMouthOpenY
   */
  _lipSyncLoop() {
    if (!this._lipSyncActive || !this._lipSyncAnalyser || !this.model) return

    const analyser = this._lipSyncAnalyser
    const dataArray = new Uint8Array(analyser.frequencyBinCount)
    analyser.getByteFrequencyData(dataArray)

    // 计算音量（RMS 简化版：取低频段平均值）
    let sum = 0
    // 只取前 16 个频率 bin（低频，对应人声）
    const binCount = Math.min(16, dataArray.length)
    for (let i = 0; i < binCount; i++) {
      sum += dataArray[i]
    }
    const avg = sum / binCount

    // 归一化到 0~1，增益系数 5.0（与黍模型 LipSync Gain=5.0 对齐）
    const mouthOpen = Math.min(1.0, (avg / 255) * 5.0)

    // 设置口型参数
    try {
      const coreModel = this.model.internalModel?.coreModel
      if (coreModel) {
        // Cubism 4 的口型参数 ID
        const paramIndex = coreModel.getParameterIndex('ParamMouthOpenY')
        if (paramIndex >= 0) {
          coreModel.setParameterValueByIndex(paramIndex, mouthOpen)
        }
      }
    } catch (e) {
      // 参数设置失败不影响后续循环
    }

    // Called after motion/physics and immediately before core model rendering.
  }

  /**
   * 停止口型同步
   */
  stopLipSync() {
    this._lipSyncActive = false
    if(this._lipSyncModel&&this._lipSyncFrame)this._lipSyncModel.off('beforeModelUpdate',this._lipSyncFrame)
    this._lipSyncModel=null;this._lipSyncFrame=null

    if (this._lipSyncRAF) {
      cancelAnimationFrame(this._lipSyncRAF)
      this._lipSyncRAF = null
    }

    // 断开 AnalyserNode
    if (this._lipSyncSource && this._lipSyncAnalyser) {
      try {
        this._lipSyncSource.disconnect(this._lipSyncAnalyser)
      } catch (e) {
        // 忽略断开失败
      }
    }
    this._lipSyncAnalyser = null
    this._lipSyncSource = null

    // 恢复口型参数为 0
    if (this.model) {
      try {
        const coreModel = this.model.internalModel?.coreModel
        if (coreModel) {
          const paramIndex = coreModel.getParameterIndex('ParamMouthOpenY')
          if (paramIndex >= 0) {
            coreModel.setParameterValueByIndex(paramIndex, 0)
          }
        }
      } catch (e) {
        // 忽略
      }
    }

    console.log('[Live2DController] lipSync stopped')
  }

  /**
   * 对话结束后恢复默认状态
   * 延迟 2s 后恢复默认表情 + 停止口型 + 恢复动作音效
   */
  onConversationEnd() {
    clearTimeout(this._conversationEndTimer)
    this._conversationEndTimer = setTimeout(() => {
      this._conversationEndTimer = null
      this.stopLipSync()
      if (!this._manualExpression) this.resetExpression()
    }, 2000)
  }
}

// 全局单例
export const live2dController = new Live2DController()
