<template>
  <div v-if="visible" class="fixed inset-0 z-[100] flex items-center justify-center bg-black/60">
    <div class="bg-white dark:bg-gray-800 rounded-xl shadow-xl w-full max-w-lg mx-4 p-6 border dark:border-gray-700">
      <h2 class="text-xl font-bold mb-2 dark:text-gray-100">首次启动配置</h2>
      <p class="text-sm text-gray-500 dark:text-gray-400 mb-4">
        LLM、Embedding、TTS 需单独部署。请填写可访问的服务地址（打包版已内置 ASR / MemOS / 联网搜索等）。
      </p>

      <div class="space-y-3">
        <div>
          <label class="block text-sm font-medium mb-1 dark:text-gray-300">LLM API Base URL</label>
          <input v-model="form.llmBaseUrl" type="text" class="w-full px-3 py-2 border rounded-md dark:bg-gray-700 dark:border-gray-600 dark:text-gray-100" placeholder="http://127.0.0.1:1234/v1" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1 dark:text-gray-300">LLM 模型名</label>
          <input v-model="form.llmModelName" type="text" class="w-full px-3 py-2 border rounded-md dark:bg-gray-700 dark:border-gray-600 dark:text-gray-100" placeholder="gemma4-e4b" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1 dark:text-gray-300">Embedding API Base URL</label>
          <input v-model="form.embeddingBaseUrl" type="text" class="w-full px-3 py-2 border rounded-md dark:bg-gray-700 dark:border-gray-600 dark:text-gray-100" placeholder="http://127.0.0.1:1234/v1" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1 dark:text-gray-300">Embedding 模型名</label>
          <input v-model="form.embeddingModelName" type="text" class="w-full px-3 py-2 border rounded-md dark:bg-gray-700 dark:border-gray-600 dark:text-gray-100" placeholder="text-embedding-embeddinggemma-300m" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1 dark:text-gray-300">TTS 服务 URL</label>
          <input v-model="form.ttsBaseUrl" type="text" class="w-full px-3 py-2 border rounded-md dark:bg-gray-700 dark:border-gray-600 dark:text-gray-100" placeholder="http://192.168.x.x:5000 或 http://localhost:5000" />
        </div>
      </div>

      <p v-if="error" class="text-red-500 text-sm mt-3">{{ error }}</p>

      <div class="flex justify-end gap-2 mt-6">
        <button @click="openLogs" class="px-4 py-2 text-sm border rounded-md dark:border-gray-600 dark:text-gray-300">打开日志</button>
        <button @click="save" :disabled="saving" class="px-4 py-2 text-sm bg-blue-600 text-white rounded-md disabled:opacity-50">
          {{ saving ? '保存中...' : '保存并继续' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { saveRuntimeConfig, runtimeConfigToSettings } from '../utils/runtimeConfig.js'

const props = defineProps({
  initialSettings: { type: Object, required: true },
})

const emit = defineEmits(['done'])

const visible = ref(true)
const saving = ref(false)
const error = ref('')

const form = reactive({
  llmBaseUrl: props.initialSettings.modelBaseUrl || 'http://127.0.0.1:1234/v1',
  llmModelName: props.initialSettings.modelName || 'gemma4-e4b',
  embeddingBaseUrl: props.initialSettings.embeddingBaseUrl || 'http://127.0.0.1:1234/v1',
  embeddingModelName: props.initialSettings.embeddingModelName || 'text-embedding-embeddinggemma-300m',
  ttsBaseUrl: props.initialSettings.astraTtsBaseUrl || 'http://localhost:5000',
})

async function save() {
  saving.value = true
  error.value = ''
  try {
    const merged = {
      ...props.initialSettings,
      modelBaseUrl: form.llmBaseUrl.trim(),
      modelName: form.llmModelName.trim(),
      llmStreamingModelName: form.llmModelName.trim(),
      embeddingBaseUrl: form.embeddingBaseUrl.trim(),
      embeddingModelName: form.embeddingModelName.trim(),
      astraTtsBaseUrl: form.ttsBaseUrl.trim(),
    }
    const saved = await saveRuntimeConfig(merged)
    if (window.electronAPI?.completeSetup) {
      await window.electronAPI.completeSetup()
    }
    visible.value = false
    emit('done', saved)
  } catch (e) {
    error.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

function openLogs() {
  window.electronAPI?.openLogsDir?.()
}
</script>
