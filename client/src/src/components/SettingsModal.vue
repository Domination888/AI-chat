<template>
  <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
    <div class="bg-white rounded-xl shadow-lg w-full max-w-md mx-4">
      <div class="flex items-center justify-between p-4 border-b">
        <h3 class="text-lg font-semibold">设置</h3>
        <button @click="$emit('close')" class="text-gray-400 hover:text-gray-600">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
      
      <div class="p-4 space-y-4 max-h-[70vh] overflow-y-auto">
        <!-- 模型配置 -->
        <div class="border rounded-lg p-3">
          <h4 class="font-semibold mb-3 flex items-center">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z" />
            </svg>
            模型配置
          </h4>
          
          <div class="space-y-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">模型Base URL</label>
              <input 
                v-model="modelBaseUrl" 
                type="text" 
                placeholder="http://localhost:1234/v1"
                class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm"
              />
            </div>
            
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">模型名称</label>
              <input 
                v-model="modelName" 
                type="text" 
                placeholder="gemma4-31b"
                class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm"
              />
            </div>
            
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600">使用自定义模型</span>
              <button 
                @click="useCustomModel = !useCustomModel"
                class="relative w-10 h-5 rounded-full transition-colors duration-200"
                :class="useCustomModel ? 'bg-blue-500' : 'bg-gray-300'"
              >
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all duration-200"
                      :class="useCustomModel ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
          </div>
        </div>

        <!-- ASR配置 -->
        <div class="border rounded-lg p-3">
          <h4 class="font-semibold mb-3 flex items-center">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
            </svg>
            语音识别 (ASR)
          </h4>
          
          <div class="space-y-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">ASR服务</label>
              <select v-model="asrService" class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm">
                <option value="sensevoice">SenseVoice</option>
                <option value="whisper">Whisper</option>
              </select>
            </div>
            
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">语言</label>
              <select v-model="asrLanguage" class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500 text-sm">
                <option value="zh">中文</option>
                <option value="en">英文</option>
                <option value="auto">自动检测</option>
              </select>
            </div>
          </div>
        </div>

        <!-- TTS配置 -->
        <div class="border rounded-lg p-3">
          <h4 class="font-semibold mb-3 flex items-center">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.536 8.464a5 5 0 010 7.072m2.828-9.9a9 9 0 010 12.728M5.586 15H4a1 1 0 01-1-1v-4a1 1 0 011-1h1.586l4.707-4.707C10.923 3.663 12 4.109 12 5v14c0 .891-1.077 1.337-1.707.707L5.586 15z" />
            </svg>
            语音合成 (TTS)
          </h4>
          
          <div class="space-y-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">语速</label>
              <input 
                v-model.number="ttsSpeed" 
                type="range" 
                min="0.5" 
                max="2.0" 
                step="0.1"
                class="w-full"
              />
              <div class="text-xs text-gray-500 text-center">{{ ttsSpeed }}x</div>
            </div>
            
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">音调</label>
              <input 
                v-model.number="ttsPitch" 
                type="range" 
                min="0.5" 
                max="1.5" 
                step="0.1"
                class="w-full"
              />
              <div class="text-xs text-gray-500 text-center">{{ ttsPitch }}x</div>
            </div>
          </div>
        </div>

        <!-- 其他设置 -->
        <div class="border rounded-lg p-3">
          <h4 class="font-semibold mb-3 flex items-center">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
            其他设置
          </h4>
          
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600">自动播放语音</span>
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
              <span class="text-sm text-gray-600">深色模式</span>
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
      
      <div class="p-4 border-t flex justify-end gap-2">
        <button @click="$emit('close')" class="px-4 py-2 text-sm border border-gray-300 rounded-md hover:bg-gray-50 transition">
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
import { ref, onMounted } from 'vue'
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
const useCustomModel = ref(false)
const asrService = ref('sensevoice')
const asrLanguage = ref('zh')
const ttsSpeed = ref(1.0)
const ttsPitch = ref(1.0)
const autoPlayTts = ref(true)
const darkMode = ref(false)

// 加载设置
onMounted(() => {
  loadSettings()
})

const loadSettings = () => {
  modelBaseUrl.value = localStorage.getItem('modelBaseUrl') || 'http://localhost:1234/v1'
  modelName.value = localStorage.getItem('modelName') || 'gemma4-31b'
  useCustomModel.value = localStorage.getItem('useCustomModel') === 'true'
  asrService.value = localStorage.getItem('asrService') || 'sensevoice'
  asrLanguage.value = localStorage.getItem('asrLanguage') || 'zh'
  ttsSpeed.value = parseFloat(localStorage.getItem('ttsSpeed')) || 1.0
  ttsPitch.value = parseFloat(localStorage.getItem('ttsPitch')) || 1.0
  autoPlayTts.value = localStorage.getItem('autoPlayTts') !== 'false'
  darkMode.value = localStorage.getItem('darkMode') === 'true'
}

const saveSettings = () => {
  localStorage.setItem('modelBaseUrl', modelBaseUrl.value)
  localStorage.setItem('modelName', modelName.value)
  localStorage.setItem('useCustomModel', useCustomModel.value.toString())
  localStorage.setItem('asrService', asrService.value)
  localStorage.setItem('asrLanguage', asrLanguage.value)
  localStorage.setItem('ttsSpeed', ttsSpeed.value.toString())
  localStorage.setItem('ttsPitch', ttsPitch.value.toString())
  localStorage.setItem('autoPlayTts', autoPlayTts.value.toString())
  localStorage.setItem('darkMode', darkMode.value.toString())
  
  ElMessage.success('设置已保存')
  emit('save', {
    modelBaseUrl: useCustomModel.value ? modelBaseUrl.value : null,
    modelName: useCustomModel.value ? modelName.value : null,
    asrService: asrService.value,
    asrLanguage: asrLanguage.value,
    ttsSpeed: ttsSpeed.value,
    ttsPitch: ttsPitch.value,
    autoPlayTts: autoPlayTts.value,
    darkMode: darkMode.value
  })
  emit('close')
}

defineExpose({
  loadSettings
})
</script>