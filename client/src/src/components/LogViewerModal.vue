<template>
  <div v-if="show" :class="embedded ? 'w-full h-[58vh] min-h-[420px]' : 'fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50'">
    <div :class="embedded ? 'w-full h-full flex flex-col border dark:border-gray-700 rounded-lg overflow-hidden' : 'bg-white dark:bg-gray-800 rounded-xl shadow-lg w-full max-w-5xl mx-4 flex flex-col max-h-[88vh]'">
      <div class="flex items-center justify-between p-4 border-b dark:border-gray-700 shrink-0">
        <div>
          <h3 class="text-lg font-semibold dark:text-gray-100">系统日志</h3>
          <p v-if="logsMode" class="text-xs text-gray-500 dark:text-gray-400 mt-0.5">
            {{ logsMode === 'dev' ? '开发环境 · unified-logs' : '整合包 · 内置服务日志' }}
          </p>
          <p v-if="logsRoot" class="text-xs text-gray-400 truncate max-w-md" :title="logsRoot">{{ logsRoot }}</p>
        </div>
        <button v-if="!embedded" @click="$emit('close')" class="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="flex flex-1 min-h-0">
        <!-- 日志源列表 -->
        <div class="w-44 shrink-0 border-r dark:border-gray-700 overflow-y-auto p-2 space-y-0.5">
          <button
            v-for="src in sources"
            :key="src.id"
            @click="selectSource(src.id)"
            class="w-full text-left px-2.5 py-2 text-xs rounded-md transition truncate"
            :class="activeId === src.id
              ? 'bg-blue-100 dark:bg-blue-900 text-blue-700 dark:text-blue-200 font-medium'
              : 'hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-600 dark:text-gray-300'"
            :title="src.id"
          >
            {{ src.label }}
          </button>
          <p v-if="!loading && sources.length === 0" class="text-xs text-gray-400 px-2 py-4 text-center leading-relaxed">
            <template v-if="apiMissing">日志接口不可用，请重启 Electron 或重新构建整合包</template>
            <template v-else-if="!dirExists">日志目录不存在</template>
            <template v-else>暂无 .log 文件</template>
          </p>
        </div>

        <!-- 日志内容 -->
        <div class="flex-1 flex flex-col min-w-0">
          <div class="flex items-center gap-2 px-3 py-2 border-b dark:border-gray-700 text-xs shrink-0">
            <span class="font-medium dark:text-gray-200 truncate">{{ activeLabel }}</span>
            <span v-if="meta.size" class="text-gray-400">{{ formatSize(meta.size) }}</span>
            <span v-if="meta.truncated" class="text-amber-500">已截断</span>
            <div class="flex-1"></div>
            <label class="flex items-center gap-1 text-gray-500 dark:text-gray-400 cursor-pointer select-none">
              <input v-model="autoRefresh" type="checkbox" class="rounded" />
              自动刷新
            </label>
            <button @click="refresh" :disabled="loading" class="px-2 py-1 border dark:border-gray-600 rounded hover:bg-gray-50 dark:hover:bg-gray-700 disabled:opacity-50">
              🔄 刷新
            </button>
            <button @click="copyContent" class="px-2 py-1 border dark:border-gray-600 rounded hover:bg-gray-50 dark:hover:bg-gray-700">
              复制
            </button>
            <button @click="openDir" class="px-2 py-1 border dark:border-gray-600 rounded hover:bg-gray-50 dark:hover:bg-gray-700">
              打开目录
            </button>
          </div>

          <div ref="scrollEl" class="flex-1 overflow-auto p-3 bg-gray-900 text-gray-100 font-mono text-xs leading-relaxed whitespace-pre-wrap break-all">
            <span v-if="loading" class="text-gray-400">加载中...</span>
            <span v-else-if="error" class="text-red-400">{{ error }}</span>
            <span v-else-if="!content" class="text-gray-500">（无内容）</span>
            <span v-else>{{ content }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  show: { type: Boolean, default: false },
  embedded: { type: Boolean, default: false },
})

defineEmits(['close'])

const sources = ref([])
const logsRoot = ref('')
const logsMode = ref('')
const dirExists = ref(true)
const apiMissing = ref(false)
const activeId = ref('')
const content = ref('')
const loading = ref(false)
const error = ref('')
const meta = ref({ size: 0, truncated: false })
const autoRefresh = ref(true)
const scrollEl = ref(null)

let refreshTimer = null

const activeLabel = ref('')

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

async function loadSources() {
  apiMissing.value = !window.electronAPI?.listLogs
  if (apiMissing.value) {
    sources.value = []
    return
  }
  try {
    const data = await window.electronAPI.listLogs()
    logsRoot.value = data.root || ''
    logsMode.value = data.mode || ''
    dirExists.value = data.exists !== false
    sources.value = data.sources || []
    if (!activeId.value && sources.value.length) {
      const preferred = sources.value.find(s => s.id.includes('backend')) || sources.value[0]
      activeId.value = preferred.id
      activeLabel.value = preferred.label
    }
  } catch (e) {
    error.value = e.message || '加载日志列表失败'
  }
}

async function refresh() {
  if (!activeId.value || !window.electronAPI?.readLog) return
  loading.value = true
  error.value = ''
  try {
    const result = await window.electronAPI.readLog(activeId.value, 500)
    content.value = result.content || ''
    meta.value = { size: result.size || 0, truncated: !!result.truncated }
    await nextTick()
    if (scrollEl.value) {
      scrollEl.value.scrollTop = scrollEl.value.scrollHeight
    }
  } catch (e) {
    error.value = e.message || '读取失败'
  } finally {
    loading.value = false
  }
}

function selectSource(id) {
  activeId.value = id
  const src = sources.value.find(s => s.id === id)
  activeLabel.value = src?.label || id
  refresh()
}

async function copyContent() {
  if (!content.value) return
  try {
    await navigator.clipboard.writeText(content.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败')
  }
}

function openDir() {
  window.electronAPI?.openLogsDir?.()
}

function startAutoRefresh() {
  stopAutoRefresh()
  if (!autoRefresh.value) return
  refreshTimer = setInterval(() => {
    if (props.show && activeId.value) refresh()
  }, 5000)
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

watch(() => props.show, async (visible) => {
  if (visible) {
    activeId.value = ''
    content.value = ''
    error.value = ''
    await loadSources()
    if (activeId.value) await refresh()
    startAutoRefresh()
  } else {
    stopAutoRefresh()
  }
})

watch(autoRefresh, () => {
  if (props.show) startAutoRefresh()
})

onMounted(() => {
  if (props.show) {
    loadSources().then(() => activeId.value && refresh())
    startAutoRefresh()
  }
})

onUnmounted(stopAutoRefresh)
</script>
