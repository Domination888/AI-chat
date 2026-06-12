<template>
  <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
    <div class="bg-white dark:bg-gray-800 rounded-xl shadow-lg w-full max-w-2xl mx-4">
      <div class="flex items-center justify-between p-4 border-b dark:border-gray-700">
        <h3 class="text-lg font-semibold dark:text-gray-100">设置</h3>
        <button @click="$emit('close')" class="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
      
      <div class="p-4 space-y-4 max-h-[70vh] overflow-y-auto">
        <!-- LLM -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">LLM 模型</h4>
          <div class="grid grid-cols-1 gap-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Base URL</label>
              <input v-model="form.modelBaseUrl" type="text" placeholder="http://127.0.0.1:1234/v1"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">模型名称</label>
                <input v-model="form.modelName" type="text" placeholder="gemma4-e4b"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">流式模型</label>
                <input v-model="form.llmStreamingModelName" type="text" placeholder="同左或留空"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">读超时 (ms)</label>
              <input v-model.number="form.llmReadTimeoutMs" type="number" min="5000" step="1000"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
          </div>
        </div>

        <!-- Embedding -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">Embedding（RAG 向量）</h4>
          <div class="grid grid-cols-1 gap-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Base URL</label>
              <input v-model="form.embeddingBaseUrl" type="text" placeholder="http://127.0.0.1:1234/v1"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">模型名称</label>
              <input v-model="form.embeddingModelName" type="text" placeholder="text-embedding-embeddinggemma-300m"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
          </div>
        </div>

        <!-- ASR -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">语音识别 (ASR)</h4>
          <div class="grid grid-cols-1 gap-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">ASR 接口 URL</label>
              <input v-model="form.asrUrl" type="text" placeholder="http://127.0.0.1:9000/v1/audio/transcriptions"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">语言</label>
                <select v-model="form.asrLanguage" class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm">
                  <option value="zh">中文</option>
                  <option value="en">英文</option>
                  <option value="auto">自动检测</option>
                </select>
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">超时 (ms)</label>
                <input v-model.number="form.asrTimeoutMs" type="number" min="3000" step="1000"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
            </div>
          </div>
        </div>

        <!-- TTS -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">语音合成 (TTS)</h4>
          <div class="grid grid-cols-1 gap-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Astra TTS 服务 URL</label>
              <input v-model="form.astraTtsBaseUrl" type="text" placeholder="http://192.168.x.x:5000"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">默认 Avatar ID</label>
                <input v-model="form.astraDefaultAvatarId" type="text" placeholder="chenxing"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">默认音色 Profile</label>
                <input v-model="form.ttsDefaultProfile" type="text" placeholder="shu"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">流式分片大小</label>
                <input v-model.number="form.astraStreamingChunkSize" type="number" min="512" step="256"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">TTS 超时 (ms)</label>
                <input v-model.number="form.ttsTimeoutMs" type="number" min="10000" step="5000"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">语速（前端覆盖）</label>
              <input v-model.number="form.ttsSpeed" type="range" min="0.5" max="2.0" step="0.1" class="w-full" />
              <div class="text-xs text-gray-500 dark:text-gray-400 text-center">{{ form.ttsSpeed }}x</div>
            </div>
          </div>
        </div>

        <!-- Memos -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">记忆服务 (Memos)</h4>
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600 dark:text-gray-400">启用 Memos</span>
              <button @click="form.memosEnabled = !form.memosEnabled"
                class="relative w-10 h-5 rounded-full transition-colors"
                :class="form.memosEnabled ? 'bg-blue-500' : 'bg-gray-300'">
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                      :class="form.memosEnabled ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Memos Base URL</label>
              <input v-model="form.memosBaseUrl" type="text" placeholder="http://localhost:8000"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">搜索 Top K</label>
                <input v-model.number="form.memosSearchTopK" type="number" min="1" max="50"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">搜索模式</label>
                <select v-model="form.memosSearchMode" class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm">
                  <option value="fast">fast</option>
                  <option value="fine">fine</option>
                  <option value="mixture">mixture</option>
                </select>
              </div>
            </div>
          </div>
        </div>

        <!-- 主动说话 -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">主动说话</h4>
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600 dark:text-gray-400">启用主动搭话</span>
              <button @click="form.proactiveChatEnabled = !form.proactiveChatEnabled"
                class="relative w-10 h-5 rounded-full transition-colors"
                :class="form.proactiveChatEnabled ? 'bg-blue-500' : 'bg-gray-300'">
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                      :class="form.proactiveChatEnabled ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
            <div v-if="form.proactiveChatEnabled">
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">搭话间隔</label>
              <input v-model.number="form.proactiveIdleSeconds" type="range"
                :min="PROACTIVE_IDLE_MIN" :max="PROACTIVE_IDLE_MAX" :step="PROACTIVE_IDLE_STEP" class="w-full" />
              <div class="text-xs text-gray-500 dark:text-gray-400 text-center">{{ formatProactiveIdle(form.proactiveIdleSeconds) }}</div>
            </div>
            <div v-if="form.proactiveChatEnabled">
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">搭话提示词</label>
              <textarea v-model="form.proactivePrompt" rows="2"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm resize-none"></textarea>
            </div>
          </div>
        </div>

        <!-- 其他 -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">其他</h4>
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600 dark:text-gray-400">自动播放语音</span>
              <button @click="form.autoPlayTts = !form.autoPlayTts"
                class="relative w-10 h-5 rounded-full transition-colors"
                :class="form.autoPlayTts ? 'bg-blue-500' : 'bg-gray-300'">
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                      :class="form.autoPlayTts ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600 dark:text-gray-400">深色模式</span>
              <button @click="form.darkMode = !form.darkMode"
                class="relative w-10 h-5 rounded-full transition-colors"
                :class="form.darkMode ? 'bg-blue-500' : 'bg-gray-300'">
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                      :class="form.darkMode ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
          </div>
        </div>

        <p class="text-xs text-gray-400 dark:text-gray-500">保存后立即写入后端并持久化到 config/runtime-config.json，无需重启服务。</p>
      </div>
      
      <div class="p-4 border-t dark:border-gray-700 flex justify-end gap-2">
        <button @click="$emit('close')" class="px-4 py-2 text-sm border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition">
          取消
        </button>
        <button @click="saveSettings" :disabled="saving" class="px-4 py-2 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700 transition disabled:opacity-50">
          {{ saving ? '保存中...' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  DEFAULT_SETTINGS,
  fetchRuntimeConfig,
  runtimeConfigToSettings,
  saveRuntimeConfig
} from '../utils/runtimeConfig.js'

const props = defineProps({
  show: { type: Boolean, default: false },
  initialSettings: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['close', 'save'])

const form = reactive({ ...DEFAULT_SETTINGS })
const saving = ref(false)

const PROACTIVE_IDLE_STEP = 1800
const PROACTIVE_IDLE_DEFAULT = 3600
const PROACTIVE_IDLE_MIN = 1800
const PROACTIVE_IDLE_MAX = 43200

const normalizeProactiveIdleSeconds = (value) => {
  const n = parseInt(value)
  if (!n || n < PROACTIVE_IDLE_MIN) return PROACTIVE_IDLE_DEFAULT
  const snapped = Math.round(n / PROACTIVE_IDLE_STEP) * PROACTIVE_IDLE_STEP
  return Math.min(PROACTIVE_IDLE_MAX, Math.max(PROACTIVE_IDLE_MIN, snapped))
}

const formatProactiveIdle = (seconds) => {
  const hours = Math.floor(seconds / 3600)
  const minutes = (seconds % 3600) / 60
  if (hours === 0) return `${minutes}分钟`
  if (minutes === 0) return `${hours}小时`
  return `${hours}小时${minutes}分钟`
}

let darkModeSnapshot = false

const applyForm = (settings) => {
  Object.assign(form, { ...DEFAULT_SETTINGS, ...settings })
  form.proactiveIdleSeconds = normalizeProactiveIdleSeconds(form.proactiveIdleSeconds)
}

const loadSettings = async () => {
  try {
    const config = await fetchRuntimeConfig()
    applyForm(runtimeConfigToSettings(config))
  } catch (e) {
    console.warn('从后端加载配置失败，使用本地值', e)
    applyForm(props.initialSettings)
  }
  darkModeSnapshot = form.darkMode
}

onMounted(() => loadSettings())

watch(() => props.show, (newVal, oldVal) => {
  if (oldVal && !newVal && form.darkMode !== darkModeSnapshot) {
    form.darkMode = darkModeSnapshot
    document.documentElement.classList.toggle('dark', darkModeSnapshot)
  }
  if (newVal) {
    loadSettings()
    darkModeSnapshot = form.darkMode
  }
})

watch(() => form.darkMode, (isDark) => {
  document.documentElement.classList.toggle('dark', isDark)
})

const saveSettings = async () => {
  form.proactiveIdleSeconds = normalizeProactiveIdleSeconds(form.proactiveIdleSeconds)
  saving.value = true
  try {
    const saved = await saveRuntimeConfig({ ...form })
    applyForm(saved)
    darkModeSnapshot = form.darkMode
    emit('save', { ...form })
    emit('close')
    ElMessage.success('配置已保存并热更新')
  } catch (e) {
    console.error(e)
    ElMessage.error('保存配置失败')
  } finally {
    saving.value = false
  }
}

defineExpose({ loadSettings })
</script>
