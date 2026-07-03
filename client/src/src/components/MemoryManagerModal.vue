<template>
  <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
    <div class="bg-white dark:bg-gray-800 rounded-xl shadow-lg w-full max-w-2xl mx-4">
      <div class="flex items-center justify-between p-4 border-b dark:border-gray-700">
        <h3 class="text-lg font-semibold dark:text-gray-100">记忆</h3>
        <button @click="$emit('close')" class="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="p-4 space-y-3 max-h-[70vh] overflow-y-auto">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="text-sm text-gray-600 dark:text-gray-400">
            <span v-if="memosEnabled">已选择 {{ selectedMemoryIds.length }} / {{ memories.length }} 条</span>
            <span v-else>Memos 未启用</span>
          </div>
          <div class="flex items-center gap-2">
            <button
              type="button"
              @click="toggleSelectAll"
              :disabled="!memosEnabled || memoryLoading || memories.length === 0"
              class="px-3 py-1.5 text-xs border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition disabled:opacity-50"
            >
              {{ allSelected ? '取消全选' : '全选' }}
            </button>
            <button
              type="button"
              @click="deleteSelectedMemories"
              :disabled="!memosEnabled || selectedMemoryIds.length === 0 || batchDeleting"
              class="px-3 py-1.5 text-xs text-red-600 border border-red-200 rounded-md hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-950/40 transition disabled:opacity-50"
            >
              {{ batchDeleting ? '删除中...' : '批量删除' }}
            </button>
            <button
              type="button"
              @click="loadMemories"
              :disabled="memoryLoading || !memosEnabled"
              class="px-3 py-1.5 text-xs border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition disabled:opacity-50"
            >
              {{ memoryLoading ? '刷新中...' : '刷新' }}
            </button>
          </div>
        </div>

        <div v-if="memoryError" class="text-xs text-red-500 dark:text-red-400">{{ memoryError }}</div>
        <div v-if="!memosEnabled" class="text-xs text-gray-500 dark:text-gray-400">请先在设置中启用 Memos。</div>
        <div v-else-if="!memoryLoading && memories.length === 0" class="text-xs text-gray-500 dark:text-gray-400">暂无可显示记忆</div>
        <div v-else class="space-y-2">
          <div
            v-for="memory in memories"
            :key="memory.id"
            class="rounded-md border border-gray-200 dark:border-gray-700 p-2 bg-gray-50 dark:bg-gray-900/30"
          >
            <div class="flex items-start gap-2">
              <input
                type="checkbox"
                :checked="selectedMemoryIds.includes(memory.id)"
                class="mt-1 rounded border-gray-300"
                @change="toggleMemory(memory.id)"
              />
              <div class="min-w-0 flex-1">
                <div class="text-sm text-gray-800 dark:text-gray-100 break-words">{{ memory.text }}</div>
                <div class="mt-1 text-[11px] text-gray-500 dark:text-gray-400">
                  {{ formatMemoryType(memory.type) }}
                  <span v-if="memory.sessionId"> · {{ memory.sessionId }}</span>
                </div>
              </div>
              <button
                type="button"
                @click="deleteMemory(memory)"
                :disabled="memoryDeletingId === memory.id || batchDeleting"
                class="shrink-0 px-2 py-1 text-xs text-red-600 border border-red-200 rounded-md hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-950/40 transition disabled:opacity-50"
              >
                {{ memoryDeletingId === memory.id ? '删除中' : '删除' }}
              </button>
            </div>
            <div class="mt-2 flex gap-2 pl-6">
              <input
                v-model="memoryFeedback[memory.id]"
                type="text"
                placeholder="修正这条记忆..."
                class="min-w-0 flex-1 px-2 py-1 text-xs border border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100 rounded-md"
              />
              <button
                type="button"
                @click="feedbackMemory(memory)"
                :disabled="memoryFeedbackId === memory.id || !memoryFeedback[memory.id]"
                class="shrink-0 px-2 py-1 text-xs text-blue-600 border border-blue-200 rounded-md hover:bg-blue-50 dark:border-blue-800 dark:text-blue-400 dark:hover:bg-blue-950/40 transition disabled:opacity-50"
              >
                {{ memoryFeedbackId === memory.id ? '提交中' : '纠错' }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="p-4 border-t dark:border-gray-700 flex justify-end">
        <button @click="$emit('close')" class="px-4 py-2 text-sm border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition">
          关闭
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { apiFetch } from '../utils/api.js'

const props = defineProps({
  show: { type: Boolean, default: false },
  memosEnabled: { type: Boolean, default: true },
  currentRoleId: { type: [Number, String], default: null }
})

defineEmits(['close'])

const memories = ref([])
const selectedMemoryIds = ref([])
const memoryLoading = ref(false)
const batchDeleting = ref(false)
const memoryDeletingId = ref('')
const memoryFeedbackId = ref('')
const memoryError = ref('')
const memoryFeedback = reactive({})

const memoryTypeLabels = {
  USER: '用户事实',
  LONG_TERM: '长期记忆',
  WORKING: '工作记忆',
  PREFERENCE: '偏好记忆',
  UNKNOWN: '未知类型'
}

const formatMemoryType = (type) => memoryTypeLabels[type] || type || '未知类型'

const allSelected = computed(() => (
  memories.value.length > 0 && selectedMemoryIds.value.length === memories.value.length
))

const memoryQuery = () => {
  const params = new URLSearchParams()
  if (props.currentRoleId !== null && props.currentRoleId !== undefined && props.currentRoleId !== '') {
    params.set('roleId', String(props.currentRoleId))
  }
  const query = params.toString()
  return query ? `?${query}` : ''
}

const loadMemories = async () => {
  if (!props.memosEnabled) return
  memoryLoading.value = true
  memoryError.value = ''
  try {
    const res = await apiFetch(`/api/memories${memoryQuery()}`)
    const data = await res.json()
    memories.value = Array.isArray(data.items) ? data.items : []
    selectedMemoryIds.value = selectedMemoryIds.value.filter(id =>
      memories.value.some(memory => memory.id === id)
    )
  } catch (e) {
    console.error(e)
    memoryError.value = e?.message || '加载记忆失败'
  } finally {
    memoryLoading.value = false
  }
}

const toggleMemory = (id) => {
  if (!id) return
  if (selectedMemoryIds.value.includes(id)) {
    selectedMemoryIds.value = selectedMemoryIds.value.filter(item => item !== id)
  } else {
    selectedMemoryIds.value = [...selectedMemoryIds.value, id]
  }
}

const toggleSelectAll = () => {
  selectedMemoryIds.value = allSelected.value ? [] : memories.value.map(memory => memory.id)
}

const removeMemoriesLocally = (ids) => {
  const idSet = new Set(ids)
  memories.value = memories.value.filter(item => !idSet.has(item.id))
  selectedMemoryIds.value = selectedMemoryIds.value.filter(id => !idSet.has(id))
}

const deleteMemoryIds = async (ids) => {
  const res = await apiFetch(`/api/memories${memoryQuery()}`, {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ memoryIds: ids })
  })
  const data = await res.json()
  if (!data.success) {
    throw new Error('删除记忆失败')
  }
}

const deleteMemory = async (memory) => {
  if (!memory?.id) return
  if (!window.confirm(`确定删除这条记忆吗？\n\n${memory.text}`)) return
  memoryDeletingId.value = memory.id
  memoryError.value = ''
  try {
    await deleteMemoryIds([memory.id])
    removeMemoriesLocally([memory.id])
    ElMessage.success('记忆已删除')
  } catch (e) {
    console.error(e)
    memoryError.value = e?.message || '删除记忆失败'
    ElMessage.error(memoryError.value)
  } finally {
    memoryDeletingId.value = ''
  }
}

const deleteSelectedMemories = async () => {
  const ids = [...selectedMemoryIds.value]
  if (ids.length === 0) return
  if (!window.confirm(`确定删除选中的 ${ids.length} 条记忆吗？`)) return
  batchDeleting.value = true
  memoryError.value = ''
  try {
    await deleteMemoryIds(ids)
    removeMemoriesLocally(ids)
    ElMessage.success(`已删除 ${ids.length} 条记忆`)
  } catch (e) {
    console.error(e)
    memoryError.value = e?.message || '批量删除记忆失败'
    ElMessage.error(memoryError.value)
  } finally {
    batchDeleting.value = false
  }
}

const feedbackMemory = async (memory) => {
  const feedback = memoryFeedback[memory?.id]?.trim()
  if (!memory?.id || !feedback) return
  memoryFeedbackId.value = memory.id
  memoryError.value = ''
  try {
    const res = await apiFetch(`/api/memories/${encodeURIComponent(memory.id)}/feedback${memoryQuery()}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ feedback })
    })
    const data = await res.json()
    if (!data.success) {
      throw new Error('提交记忆纠错失败')
    }
    memoryFeedback[memory.id] = ''
    ElMessage.success('记忆纠错已提交')
    await loadMemories()
  } catch (e) {
    console.error(e)
    memoryError.value = e?.message || '提交记忆纠错失败'
    ElMessage.error(memoryError.value)
  } finally {
    memoryFeedbackId.value = ''
  }
}

watch(() => props.show, (show) => {
  if (show) {
    loadMemories()
  } else {
    selectedMemoryIds.value = []
  }
})

watch(() => props.currentRoleId, () => {
  if (props.show) loadMemories()
})
</script>
