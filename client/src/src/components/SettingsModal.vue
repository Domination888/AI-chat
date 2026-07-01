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
            <div v-if="form.recentLlmModels.length" class="space-y-2">
              <div class="text-sm font-medium text-gray-700 dark:text-gray-300">最近使用</div>
              <div class="grid grid-cols-1 gap-2">
                <button
                  v-for="model in form.recentLlmModels"
                  :key="`${model.modelName}-${model.baseUrl}`"
                  type="button"
                  @click="selectRecentLlmModel(model)"
                  class="w-full min-w-0 rounded-md border border-gray-200 dark:border-gray-700 px-3 py-2 text-left hover:bg-gray-50 dark:hover:bg-gray-700 transition"
                >
                  <div class="truncate text-sm font-medium text-gray-800 dark:text-gray-100">{{ model.modelName }}</div>
                  <div class="truncate text-xs text-gray-500 dark:text-gray-400">{{ model.baseUrl }}</div>
                </button>
              </div>
            </div>
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
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">相关性阈值</label>
                <input v-model.number="form.memosRelativity" type="number" min="0" max="1" step="0.05"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">去重策略</label>
                <select v-model="form.memosDedup" class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm">
                  <option value="mmr">mmr</option>
                  <option value="sim">sim</option>
                  <option value="no">no</option>
                </select>
              </div>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
                <input v-model="form.memosIncludePreference" type="checkbox" class="rounded border-gray-300" />
                偏好记忆
              </label>
              <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
                <input v-model="form.memosSaveAssistantTurns" type="checkbox" class="rounded border-gray-300" />
                保存助手回复
              </label>
              <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
                <input v-model="form.memosSearchToolMemory" type="checkbox" class="rounded border-gray-300" />
                工具记忆
              </label>
              <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
                <input v-model="form.memosIncludeSkillMemory" type="checkbox" class="rounded border-gray-300" />
                技能记忆
              </label>
            </div>
            <div class="border-t dark:border-gray-700 pt-3">
              <div class="flex items-center justify-between mb-2">
                <span class="text-sm font-medium text-gray-700 dark:text-gray-300">已保存记忆</span>
                <button
                  type="button"
                  @click="loadMemories"
                  :disabled="memoryLoading || !form.memosEnabled"
                  class="px-3 py-1.5 text-xs border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition disabled:opacity-50"
                >
                  {{ memoryLoading ? '刷新中...' : '刷新' }}
                </button>
              </div>
              <div v-if="memoryError" class="text-xs text-red-500 dark:text-red-400 mb-2">{{ memoryError }}</div>
              <div v-if="!form.memosEnabled" class="text-xs text-gray-500 dark:text-gray-400">Memos 未启用</div>
              <div v-else-if="!memoryLoading && memories.length === 0" class="text-xs text-gray-500 dark:text-gray-400">暂无可显示记忆</div>
              <div v-else class="space-y-2 max-h-56 overflow-y-auto pr-1">
                <div
                  v-for="memory in memories"
                  :key="memory.id"
                  class="rounded-md border border-gray-200 dark:border-gray-700 p-2 bg-gray-50 dark:bg-gray-900/30"
                >
                  <div class="flex items-start justify-between gap-2">
                    <div class="min-w-0">
                      <div class="text-sm text-gray-800 dark:text-gray-100 break-words">{{ memory.text }}</div>
                      <div class="mt-1 text-[11px] text-gray-500 dark:text-gray-400">
                        {{ formatMemoryType(memory.type) }}
                        <span v-if="memory.sessionId"> · {{ memory.sessionId }}</span>
                      </div>
                    </div>
                    <button
                      type="button"
                      @click="deleteMemory(memory)"
                      :disabled="memoryDeletingId === memory.id"
                      class="shrink-0 px-2 py-1 text-xs text-red-600 border border-red-200 rounded-md hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-950/40 transition disabled:opacity-50"
                    >
                      {{ memoryDeletingId === memory.id ? '删除中' : '删除' }}
                    </button>
                  </div>
                  <div class="mt-2 flex gap-2">
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

        <!-- Live2D -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">Live2D</h4>
          <div class="space-y-4">
            <div>
              <div class="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">动作</div>
              <div class="grid grid-cols-3 sm:grid-cols-4 gap-2">
                <button
                  v-for="motion in allLive2dMotions"
                  :key="`${motion.group}-${motion.index}`"
                  type="button"
                  @click="playLive2dMotion(motion)"
                  class="px-3 py-2 text-xs border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition"
                >
                  {{ motion.name }}
                </button>
              </div>
            </div>
            <div>
              <div class="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">表情</div>
              <div class="grid grid-cols-3 sm:grid-cols-5 gap-2">
                <button
                  v-for="expression in allLive2dExpressions"
                  :key="expression"
                  type="button"
                  @click="playLive2dExpression(expression)"
                  class="px-3 py-2 text-xs border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition"
                >
                  {{ expression }}
                </button>
                <button
                  type="button"
                  @click="resetLive2dExpression"
                  class="px-3 py-2 text-xs border border-amber-300 text-amber-700 dark:border-amber-700 dark:text-amber-300 rounded-md hover:bg-amber-50 dark:hover:bg-amber-950/40 transition"
                >
                  重置
                </button>
              </div>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">模型大小</label>
              <input
                v-model.number="live2dScale"
                type="range"
                min="0.3"
                max="2.0"
                step="0.05"
                class="w-full"
                @input="setLive2dScale"
              />
              <div class="text-xs text-gray-500 dark:text-gray-400 text-center">{{ live2dScale.toFixed(2) }}x</div>
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
  rememberRecentLlmModel,
  runtimeConfigToSettings,
  saveRuntimeConfig
} from '../utils/runtimeConfig.js'
import { apiFetch } from '../utils/api.js'
import { allLive2dExpressions, allLive2dMotions } from '../live2d/live2d-options.js'

const props = defineProps({
  show: { type: Boolean, default: false },
  initialSettings: { type: Object, default: () => ({}) },
  currentRoleId: { type: [Number, String], default: null }
})

const emit = defineEmits(['close', 'save'])

const form = reactive({ ...DEFAULT_SETTINGS })
const saving = ref(false)
const memories = ref([])
const memoryLoading = ref(false)
const memoryDeletingId = ref('')
const memoryFeedbackId = ref('')
const memoryError = ref('')
const memoryFeedback = reactive({})
const live2dScale = ref(1.0)

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

const selectRecentLlmModel = (model) => {
  form.modelBaseUrl = model.baseUrl
  form.modelName = model.modelName
  form.llmStreamingModelName = model.streamingModelName || model.modelName
}

const sendLive2dControl = (action, data = null) => {
  if (window.electronAPI?.live2dControl) {
    window.electronAPI.live2dControl(action, data)
  }
}

const playLive2dMotion = (motion) => {
  sendLive2dControl('motion', motion)
}

const playLive2dExpression = (name) => {
  sendLive2dControl('expression', { name })
}

const resetLive2dExpression = () => {
  sendLive2dControl('reset')
}

const setLive2dScale = () => {
  sendLive2dControl('scale', { scale: live2dScale.value })
}

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

const memoryTypeLabels = {
  USER: '用户事实',
  LONG_TERM: '长期记忆',
  WORKING: '工作记忆',
  PREFERENCE: '偏好记忆',
  UNKNOWN: '未知类型'
}

const formatMemoryType = (type) => memoryTypeLabels[type] || type || '未知类型'

const memoryQuery = () => {
  const params = new URLSearchParams()
  if (props.currentRoleId !== null && props.currentRoleId !== undefined && props.currentRoleId !== '') {
    params.set('roleId', String(props.currentRoleId))
  }
  const query = params.toString()
  return query ? `?${query}` : ''
}

const loadMemories = async () => {
  if (!form.memosEnabled) return
  memoryLoading.value = true
  memoryError.value = ''
  try {
    const res = await apiFetch(`/api/memories${memoryQuery()}`)
    const data = await res.json()
    memories.value = Array.isArray(data.items) ? data.items : []
  } catch (e) {
    console.error(e)
    memoryError.value = e?.message || '加载记忆失败'
  } finally {
    memoryLoading.value = false
  }
}

const deleteMemory = async (memory) => {
  if (!memory?.id) return
  if (!window.confirm(`确定删除这条记忆吗？\n\n${memory.text}`)) return
  memoryDeletingId.value = memory.id
  memoryError.value = ''
  try {
    const res = await apiFetch(`/api/memories/${encodeURIComponent(memory.id)}${memoryQuery()}`, {
      method: 'DELETE'
    })
    const data = await res.json()
    if (!data.success) {
      throw new Error('删除记忆失败')
    }
    memories.value = memories.value.filter(item => item.id !== memory.id)
    ElMessage.success('记忆已删除')
  } catch (e) {
    console.error(e)
    memoryError.value = e?.message || '删除记忆失败'
    ElMessage.error(memoryError.value)
  } finally {
    memoryDeletingId.value = ''
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

onMounted(async () => {
  await loadSettings()
  if (props.show) {
    await loadMemories()
  }
})

watch(() => props.show, (newVal, oldVal) => {
  if (oldVal && !newVal && form.darkMode !== darkModeSnapshot) {
    form.darkMode = darkModeSnapshot
    document.documentElement.classList.toggle('dark', darkModeSnapshot)
  }
  if (newVal) {
    loadSettings().then(loadMemories)
    darkModeSnapshot = form.darkMode
  }
})

watch(() => form.darkMode, (isDark) => {
  document.documentElement.classList.toggle('dark', isDark)
})

const saveSettings = async () => {
  form.proactiveIdleSeconds = normalizeProactiveIdleSeconds(form.proactiveIdleSeconds)
  form.recentLlmModels = rememberRecentLlmModel(form, form.recentLlmModels)
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
