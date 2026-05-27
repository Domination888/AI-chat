<template>
  <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
    <div class="bg-white dark:bg-gray-800 rounded-xl shadow-lg w-full max-w-md mx-4">
      <div class="flex items-center justify-between p-4 border-b dark:border-gray-700">
        <h3 class="text-lg font-semibold dark:text-gray-100">设置</h3>
        <button @click="$emit('close')" class="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
      
      <div class="p-4 space-y-4 max-h-[70vh] overflow-y-auto">
        <!-- 模型配置 -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3 flex items-center">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z" />
            </svg>
            模型配置
          </h4>
          
          <div class="space-y-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">模型Base URL</label>
              <input 
                v-model="modelBaseUrl" 
                type="text" 
                placeholder="http://localhost:1234/v1"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm"
              />
            </div>
            
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">模型名称</label>
              <input 
                v-model="modelName" 
                type="text" 
                placeholder="gemma4-31b"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm"
              />
            </div>
            
            <p class="text-xs text-gray-400 dark:text-gray-500 mt-1">留空则使用后端默认配置</p>
          </div>
        </div>

        <!-- ASR配置 -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3 flex items-center">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
            </svg>
            语音识别 (ASR)
          </h4>
          
          <div class="space-y-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">ASR服务</label>
              <select v-model="asrService" class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm">
                <option value="sensevoice">SenseVoice</option>
                <option value="whisper">Whisper</option>
              </select>
            </div>
            
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">语言</label>
              <select v-model="asrLanguage" class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm">
                <option value="zh">中文</option>
                <option value="en">英文</option>
                <option value="auto">自动检测</option>
              </select>
            </div>
          </div>
        </div>

        <!-- TTS配置 -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3 flex items-center">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.536 8.464a5 5 0 010 7.072m2.828-9.9a9 9 0 010 12.728M5.586 15H4a1 1 0 01-1-1v-4a1 1 0 011-1h1.586l4.707-4.707C10.923 3.663 12 4.109 12 5v14c0 .891-1.077 1.337-1.707.707L5.586 15z" />
            </svg>
            语音合成 (TTS)
          </h4>
          
          <div class="space-y-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">语速</label>
              <input 
                v-model.number="ttsSpeed" 
                type="range" 
                min="0.5" 
                max="2.0" 
                step="0.1"
                class="w-full"
              />
              <div class="text-xs text-gray-500 dark:text-gray-400 text-center">{{ ttsSpeed }}x</div>
            </div>
            
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">音调</label>
              <input 
                v-model.number="ttsPitch" 
                type="range" 
                min="0.5" 
                max="1.5" 
                step="0.1"
                class="w-full"
              />
              <div class="text-xs text-gray-500 dark:text-gray-400 text-center">{{ ttsPitch }}x</div>
            </div>
          </div>
        </div>

        <!-- 主动说话 -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3 flex items-center">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
            </svg>
            主动说话
          </h4>
          
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600 dark:text-gray-400">启用主动搭话</span>
              <button 
                @click="proactiveChatEnabled = !proactiveChatEnabled"
                class="relative w-10 h-5 rounded-full transition-colors duration-200"
                :class="proactiveChatEnabled ? 'bg-blue-500' : 'bg-gray-300'"
              >
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all duration-200"
                      :class="proactiveChatEnabled ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
            
            <div v-if="proactiveChatEnabled">
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">空闲时间（秒）</label>
              <input 
                v-model.number="proactiveIdleSeconds" 
                type="range" 
                min="5" 
                max="120" 
                step="5"
                class="w-full"
              />
              <div class="text-xs text-gray-500 dark:text-gray-400 text-center">{{ proactiveIdleSeconds }}秒</div>
            </div>
            
            <div v-if="proactiveChatEnabled">
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">搭话提示词</label>
              <textarea 
                v-model="proactivePrompt" 
                rows="2"
                placeholder="AI 主动搭话时使用的系统提示词"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm resize-none"
              ></textarea>
            </div>
          </div>
        </div>

        <!-- 其他设置 -->
        <div class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3 flex items-center">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
            其他设置
          </h4>
          
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600 dark:text-gray-400">自动播放语音</span>
              <button 
                @click="autoPlayTts = !autoPlayTts"
                class="relative w-10 h-5 rounded-full transition-colors duration-200"
                :class="autoPlayTts ? 'bg-blue-500' : 'bg-gray-300'"
              >
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all duration-200"
                      :class="autoPlayTts ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
            
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600 dark:text-gray-400">深色模式</span>
              <button 
                @click="darkMode = !darkMode"
                class="relative w-10 h-5 rounded-full transition-colors duration-200"
                :class="darkMode ? 'bg-blue-500' : 'bg-gray-300'"
              >
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all duration-200"
                      :class="darkMode ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
          </div>
        </div>
      </div>
      
      <div class="p-4 border-t dark:border-gray-700 flex justify-end gap-2">
        <button @click="$emit('close')" class="px-4 py-2 text-sm border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition">
          取消
        </button>
        <button @click="saveSettings" class="px-4 py-2 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700 transition">
          保存
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  show: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['close', 'save'])

// 设置状态
const modelBaseUrl = ref('')
const modelName = ref('')
const asrService = ref('sensevoice')
const asrLanguage = ref('zh')
const ttsSpeed = ref(1.0)
const ttsPitch = ref(1.0)
const autoPlayTts = ref(true)
const darkMode = ref(false)
const proactiveChatEnabled = ref(true)
const proactiveIdleSeconds = ref(30)
const proactivePrompt = ref('[System: 用户长时间未说话，请根据上下文主动搭话，自然地延续对话]')

// 加载设置
onMounted(() => {
  loadSettings()
})

// 深色模式实时切换：点击开关立即生效，不等到保存
watch(darkMode, (isDark) => {
  if (isDark) {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }
})

// 关闭设置对话框时（取消/点X），回滚 darkMode 到上次保存的状态
watch(() => props.show, (newVal, oldVal) => {
  if (oldVal && !newVal) {
    // 对话框从开→关，如果没走 saveSettings 则回滚
    if (darkMode.value !== darkModeSnapshot) {
      darkMode.value = darkModeSnapshot
      if (darkModeSnapshot) {
        document.documentElement.classList.add('dark')
      } else {
        document.documentElement.classList.remove('dark')
      }
    }
  }
  // 对话框打开时刷新快照
  if (newVal) {
    loadSettings()
    darkModeSnapshot = darkMode.value
  }
})

// 保存前的 darkMode 快照，取消时回滚
let darkModeSnapshot = false

const loadSettings = async () => {
  // 优先从后端获取当前生效的 LLM 配置
  try {
    const res = await fetch('/api/llm-config')
    if (res.ok) {
      const config = await res.json()
      modelBaseUrl.value = config.baseUrl || ''
      modelName.value = config.modelName || ''
    } else {
      // 后端不可用时 fallback 到 localStorage
      modelBaseUrl.value = localStorage.getItem('modelBaseUrl') || ''
      modelName.value = localStorage.getItem('modelName') || ''
    }
  } catch (e) {
    modelBaseUrl.value = localStorage.getItem('modelBaseUrl') || ''
    modelName.value = localStorage.getItem('modelName') || ''
  }
  asrService.value = localStorage.getItem('asrService') || 'sensevoice'
  asrLanguage.value = localStorage.getItem('asrLanguage') || 'zh'
  ttsSpeed.value = parseFloat(localStorage.getItem('ttsSpeed')) || 1.0
  ttsPitch.value = parseFloat(localStorage.getItem('ttsPitch')) || 1.0
  autoPlayTts.value = localStorage.getItem('autoPlayTts') !== 'false'
  darkMode.value = localStorage.getItem('darkMode') === 'true'
  proactiveChatEnabled.value = localStorage.getItem('proactiveChatEnabled') !== 'false'
  proactiveIdleSeconds.value = parseInt(localStorage.getItem('proactiveIdleSeconds')) || 30
  proactivePrompt.value = localStorage.getItem('proactivePrompt') || '[System: 用户长时间未说话，请根据上下文主动搭话，自然地延续对话]'
  darkModeSnapshot = darkMode.value
}

const saveSettings = async () => {
  localStorage.setItem('modelBaseUrl', modelBaseUrl.value)
  localStorage.setItem('modelName', modelName.value)
  localStorage.setItem('asrService', asrService.value)
  localStorage.setItem('asrLanguage', asrLanguage.value)
  localStorage.setItem('ttsSpeed', ttsSpeed.value.toString())
  localStorage.setItem('ttsPitch', ttsPitch.value.toString())
  localStorage.setItem('autoPlayTts', autoPlayTts.value.toString())
  localStorage.setItem('darkMode', darkMode.value.toString())
  localStorage.setItem('proactiveChatEnabled', proactiveChatEnabled.value.toString())
  localStorage.setItem('proactiveIdleSeconds', proactiveIdleSeconds.value.toString())
  localStorage.setItem('proactivePrompt', proactivePrompt.value)

  // 同步 LLM 配置到后端（热加载，立即生效）
  const baseUrl = modelBaseUrl.value?.trim()
  const mName = modelName.value?.trim()
  if (baseUrl || mName) {
    try {
      await fetch('/api/llm-config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ baseUrl: baseUrl || undefined, modelName: mName || undefined })
      })
    } catch (e) {
      console.warn('同步 LLM 配置到后端失败', e)
    }
  }
  
  emit('save', {
    modelBaseUrl: modelBaseUrl.value || null,
    modelName: modelName.value || null,
    asrService: asrService.value,
    asrLanguage: asrLanguage.value,
    ttsSpeed: ttsSpeed.value,
    ttsPitch: ttsPitch.value,
    autoPlayTts: autoPlayTts.value,
    darkMode: darkMode.value,
    proactiveChatEnabled: proactiveChatEnabled.value,
    proactiveIdleSeconds: proactiveIdleSeconds.value,
    proactivePrompt: proactivePrompt.value
  })
  darkModeSnapshot = darkMode.value
  emit('close')
}

defineExpose({
  loadSettings
})
</script>