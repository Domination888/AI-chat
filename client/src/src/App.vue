<template>
  <div class="flex h-screen overflow-hidden bg-white dark:bg-gray-900 text-gray-800 dark:text-gray-100">
    <!-- Login Overlay -->
    <div v-if="!user" class="absolute inset-0 z-50 flex items-center justify-center bg-gray-50 dark:bg-gray-900">
      <div class="bg-white dark:bg-gray-800 p-8 rounded-xl shadow-md w-full max-w-sm border border-gray-100 dark:border-gray-700">
        <h2 class="text-2xl font-bold mb-6 text-center text-gray-800 dark:text-gray-100">登录 AI Chat</h2>
        <div class="space-y-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">用户名</label>
            <input v-model="loginForm.username" type="text" class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 dark:bg-gray-700 dark:text-gray-100" placeholder="任意用户名(自动注册)" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">密码</label>
            <input v-model="loginForm.password" type="password" class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 dark:bg-gray-700 dark:text-gray-100" @keydown.enter="doLogin" placeholder="请输入密码" />
          </div>
          <p v-if="loginError" class="text-red-500 text-sm">{{ loginError }}</p>
          <button @click="doLogin" :disabled="isLoggingIn" class="w-full py-2 px-4 bg-blue-600 text-white rounded-md hover:bg-blue-700 transition disabled:opacity-50 mt-4">
            {{ isLoggingIn ? '登录中...' : '登 录' }}
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="showMobileSidebar"
      class="fixed inset-0 z-40 bg-black/40 backdrop-blur-[1px] md:hidden"
      @click="showMobileSidebar = false"
    ></div>

    <!-- Sidebar / mobile conversation drawer -->
    <aside
      class="fixed inset-y-0 left-0 z-50 w-72 md:relative md:z-auto md:w-64 bg-gray-50 dark:bg-gray-800 border-r dark:border-gray-700 flex flex-col transform transition-transform duration-200 md:translate-x-0"
      :class="showMobileSidebar ? 'translate-x-0' : '-translate-x-full'"
    >
      <div class="p-4 border-b dark:border-gray-700 space-y-3">
        <div class="md:hidden flex items-center justify-between">
          <span class="text-sm font-semibold dark:text-gray-100">对话</span>
          <button @click="showMobileSidebar = false" class="w-8 h-8 rounded-lg hover:bg-gray-200 dark:hover:bg-gray-700 text-gray-500" aria-label="关闭会话列表">✕</button>
        </div>
        <!-- Role Selector -->
        <div>
          <button 
            @click="showRoleSelector = true"
            class="w-full flex items-center gap-2 px-3 py-2 bg-white dark:bg-gray-700 border border-gray-300 dark:border-gray-600 rounded-md hover:bg-gray-50 dark:hover:bg-gray-600 transition text-sm"
          >
            <div class="w-6 h-6 rounded-full bg-gradient-to-br from-pink-400 to-purple-500 flex items-center justify-center text-white font-bold text-xs flex-shrink-0">
              {{ selectedRole?.name?.charAt(0) || '?' }}
            </div>
            <span class="flex-1 text-left truncate">{{ selectedRole?.name || '选择角色' }}</span>
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
            </svg>
          </button>
        </div>
        <button @click="newChat" class="w-full flex items-center justify-center gap-2 bg-white dark:bg-gray-700 border border-gray-300 dark:border-gray-600 rounded-md py-2 px-4 hover:bg-gray-50 dark:hover:bg-gray-600 transition">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
            <path fill-rule="evenodd" d="M10 3a1 1 0 011 1v5h5a1 1 0 110 2h-5v5a1 1 0 11-2 0v-5H4a1 1 0 110-2h5V4a1 1 0 011-1z" clip-rule="evenodd" />
          </svg>
          新对话
        </button>
      </div>
      <div class="flex-1 overflow-y-auto p-2 space-y-1 no-scrollbar">
        <div v-for="conv in conversations" :key="conv.id"
             @click="selectConversation(conv.id)"
             class="group flex items-center justify-between px-3 py-3 text-sm rounded-md cursor-pointer transition-colors overflow-hidden"
             :class="currentConversationId === conv.id ? 'bg-blue-100 dark:bg-blue-900 text-blue-700 dark:text-blue-200 font-medium' : 'hover:bg-gray-200 dark:hover:bg-gray-700 text-gray-700 dark:text-gray-300'">
            <span class="truncate block flex-1">{{ conv.title || '新对话' }}</span>
            <button @click.stop="deleteConversation(conv.id)" title="删除对话" class="text-gray-400 hover:text-red-500 opacity-100 md:opacity-0 md:group-hover:opacity-100 transition-opacity ml-2 shrink-0">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
              </svg>
            </button>
        </div>
      </div>
      <!-- User profile block at bottom of sidebar -->
      <div class="p-4 border-t dark:border-gray-700">
        <div class="flex items-center gap-2 overflow-hidden mb-2">
          <div class="w-8 h-8 bg-blue-500 text-white rounded-full flex items-center justify-center font-bold flex-shrink-0">
            {{ user?.username?.charAt(0).toUpperCase() }}
          </div>
          <div class="text-sm font-medium truncate dark:text-gray-200">{{ user?.username }}</div>
        </div>
        <div class="grid grid-cols-2 gap-2">
          <button @click="openSettings" class="text-xs font-medium border border-gray-300 dark:border-gray-600 rounded-lg py-2 hover:bg-white dark:hover:bg-gray-700 transition">
            ⚙ 设置中心
          </button>
          <button @click="logout" class="text-xs text-gray-500 dark:text-gray-400 hover:text-red-500 border border-gray-300 dark:border-gray-600 rounded-lg py-2 transition">
            退出
          </button>
        </div>
      </div>
    </aside>

    <!-- Main Chat Area -->
    <div class="flex-1 flex flex-col relative min-h-0">
      <!-- Header -->
      <div class="h-14 border-b dark:border-gray-700 flex items-center px-3 justify-between bg-white/95 dark:bg-gray-800/95 md:hidden shrink-0">
        <button @click="showMobileSidebar = true" class="w-9 h-9 flex items-center justify-center rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700" aria-label="打开会话列表">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" /></svg>
        </button>
        <button 
          @click="showRoleSelector = true"
          class="flex items-center gap-1.5 text-sm text-gray-700 dark:text-gray-300"
        >
          <div class="w-6 h-6 rounded-full bg-gradient-to-br from-pink-400 to-purple-500 flex items-center justify-center text-white font-bold text-xs flex-shrink-0">
            {{ selectedRole?.name?.charAt(0) || '?' }}
          </div>
          <span class="truncate max-w-[120px]">{{ selectedRole?.name || '选择角色' }}</span>
        </button>
        <button @click="openSettings" class="w-9 h-9 flex items-center justify-center rounded-lg text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-700" aria-label="打开设置">⚙</button>
      </div>

      <!-- Messages List -->
      <div class="flex-1 overflow-y-auto p-4 md:p-8 no-scrollbar min-h-0 bg-gray-50/40 dark:bg-gray-900/40" id="chat-container">
        <div class="max-w-4xl mx-auto w-full min-h-full space-y-6">
        <div v-if="messages.length === 0" class="min-h-[60vh] flex flex-col items-center justify-center text-center text-gray-400 dark:text-gray-500 px-4">
          <div class="w-14 h-14 mb-5 rounded-2xl bg-gradient-to-br from-blue-500 to-violet-500 text-white flex items-center justify-center text-2xl shadow-lg shadow-blue-500/20">✦</div>
          <h2 class="text-xl font-semibold mb-2 text-gray-700 dark:text-gray-200">开始一段新对话</h2>
          <p class="text-sm mb-5">正在与 <span class="text-blue-600 dark:text-blue-400 font-medium">{{ selectedRole?.name || '当前角色' }}</span> 对话</p>
          <div class="flex flex-wrap justify-center gap-2 text-xs">
            <span class="px-3 py-1.5 rounded-full bg-white dark:bg-gray-800 border dark:border-gray-700">支持图片</span>
            <span class="px-3 py-1.5 rounded-full bg-white dark:bg-gray-800 border dark:border-gray-700">语音交互</span>
            <span class="px-3 py-1.5 rounded-full bg-white dark:bg-gray-800 border dark:border-gray-700">按需联网</span>
          </div>
        </div>
        
        <div v-for="(msg, index) in messages" :key="index" class="flex gap-4" :class="{'flex-row-reverse': msg.role === 'user'}">
          <!-- Avatar -->
          <div class="w-8 h-8 rounded-full flex items-center justify-center text-white shrink-0 text-sm"
               :class="msg.role === 'user' ? 'bg-blue-500' : 'bg-pink-500'">
            {{ msg.role === 'user' ? 'U' : (selectedRole?.name?.charAt(0) || 'AI') }}
          </div>
          <!-- Bubble -->
          <div class="max-w-[80%] rounded-2xl px-4 py-3 text-sm leading-relaxed"
               :class="msg.role === 'user' ? 'bg-blue-100 dark:bg-blue-900 text-blue-900 dark:text-blue-100 rounded-tr-sm' : 'bg-gray-100 dark:bg-gray-700 text-gray-800 dark:text-gray-100 rounded-tl-sm'">
            
            <div v-if="msg.images && msg.images.length > 0" class="flex flex-wrap gap-2 mb-2">
              <img v-for="(img, idx) in msg.images" :key="idx" :src="img" class="max-w-[200px] max-h-[200px] rounded-md object-cover border border-gray-200 dark:border-gray-600" />
            </div>
            
            <div v-if="msg.isAudio" class="flex items-center gap-2 mb-2 text-pink-600 font-bold">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 animate-pulse" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M9.383 3.076A1 1 0 0110 4v12a1 1 0 01-1.707.707L4.586 13H2a1 1 0 011-1V8a1 1 0 011-1h2.586l3.707-3.707a1 1 0 011.09-.217zM14.657 2.929a1 1 0 011.414 0A9.972 9.972 0 0119 10a9.972 9.972 0 01-2.929 7.071 1 1 0 01-1.414-1.414A7.971 7.971 0 0017 10c0-2.21-.894-4.208-2.343-5.657a1 1 0 010-1.414zm-2.829 2.828a1 1 0 011.415 0A5.983 5.983 0 0115 10a5.984 5.984 0 01-1.757 4.243 1 1 0 01-1.415-1.415A3.984 3.984 0 0013 10a3.983 3.983 0 00-1.172-2.828 1 1 0 010-1.415z" clip-rule="evenodd" />
              </svg>
              <span>[语音交互消息]</span>
            </div>

            <div style="white-space: pre-wrap; word-wrap: break-word;">{{ msg.content }}</div>

            <div v-if="msg.searchStatus" class="mt-2 text-xs text-emerald-600 dark:text-emerald-400">
              🔎 {{ msg.searchStatus }}
            </div>

            <div v-if="msg.sources?.length" class="mt-3 space-y-1.5 border-t border-gray-200 dark:border-gray-600 pt-2">
              <div class="text-xs font-medium text-gray-500 dark:text-gray-400">来源</div>
              <a v-for="(source, sourceIndex) in msg.sources" :key="source.url || sourceIndex"
                 :href="source.url" target="_blank" rel="noopener noreferrer"
                 class="block text-xs text-blue-600 dark:text-blue-400 hover:underline truncate">
                [{{ sourceIndex + 1 }}] {{ source.title || source.url }}
              </a>
            </div>

            <div v-if="msg.candidateId && !msg.feedback" class="mt-2 flex gap-2">
              <button @click="sendProactiveFeedback(msg, 'interested')" class="text-xs px-2 py-1 rounded bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-300">感兴趣</button>
              <button @click="sendProactiveFeedback(msg, 'less_like')" class="text-xs px-2 py-1 rounded bg-gray-200 text-gray-600 dark:bg-gray-600 dark:text-gray-300">少推此类</button>
            </div>
            <div v-else-if="msg.feedback" class="mt-2 text-xs text-gray-400">已反馈：{{ msg.feedback === 'interested' ? '感兴趣' : '少推此类' }}</div>
            
            <button v-if="msg.audioUrl && msg.role === 'ai'" @click="playAudio(msg.audioUrl)" class="mt-2 text-xs bg-pink-100 dark:bg-pink-900 hover:bg-pink-200 dark:hover:bg-pink-800 text-pink-700 dark:text-pink-300 py-1 px-2 rounded flex items-center gap-1 transition">
              ▶ 播放语音
            </button>
          </div>
        </div>
        </div>
      </div>

      <!-- Input Area -->
      <div class="bg-white dark:bg-gray-800 pt-4 pb-6 px-4 md:px-8 border-t border-gray-100 dark:border-gray-700 flex-shrink-0">
        <div class="max-w-3xl mx-auto flex flex-col gap-2">
          
          <div class="flex flex-wrap items-center justify-between gap-2 px-1 sm:px-2">
            <div class="flex items-center gap-2">
              <button @click="toggleVoiceMode" class="text-xs px-3 py-1 rounded-full transition" :class="isVoiceMode ? 'bg-pink-500 text-white' : 'bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300'">
                {{ isVoiceMode ? '🎙️ 按住说话模式' : '⌨️ 键盘输入模式' }}
              </button>
              <!-- 自动解锁：默认隐藏；仅在浏览器还没解锁自动播放时作为兜底 -->
              <button v-if="!audioUnlocked" @click="forceEnableAudio"
                      class="text-xs px-3 py-1 rounded-full bg-amber-100 text-amber-700 hover:bg-amber-200 transition">
                🔊 启用语音
              </button>

            </div>

            <!-- Toggles：仅展示"联网"。RAG 和本地工具默认常开（详见 PLAN-001） -->
            <div class="flex items-center gap-5">

              <!-- Web Search：后端 Search-RAG，默认关闭，由用户手动开启 -->
              <label class="flex items-center cursor-pointer select-none" title="联网搜索（本地 SearXNG）">
                <span class="mr-2 text-xs font-medium transition-colors"
                      :class="useSearch ? 'text-emerald-600' : 'text-gray-500 dark:text-gray-400'">联网</span>
                <button type="button"
                        @click.prevent="toggleSearch"
                        class="relative w-10 h-5 rounded-full transition-colors duration-200"
                        :class="useSearch ? 'bg-emerald-500' : 'bg-gray-300'">
                  <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all duration-200"
                        :class="useSearch ? 'left-[22px]' : 'left-0.5'"></span>
                </button>
              </label>
            </div>
          </div>

          <div class="relative flex flex-col border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 rounded-xl shadow-sm overflow-hidden focus-within:ring-1 focus-within:ring-blue-500 focus-within:border-blue-500 min-h-[56px] justify-center text-center">
            
            <template v-if="isVoiceMode">
              <button 
                @mousedown.prevent="startRecording" 
                @mouseup.prevent="stopRecording" 
                @mouseleave.prevent="stopRecording"
                @touchstart.prevent="startRecording"
                @touchend.prevent="stopRecording"
                class="w-full py-4 font-bold text-gray-700 dark:text-gray-200 transition"
                :class="isRecording ? 'bg-pink-100 dark:bg-pink-900 text-pink-600 dark:text-pink-300' : 'hover:bg-gray-50 dark:hover:bg-gray-600'"
                :disabled="loading"
              >
                {{ isRecording ? '🔊 松手发送...' : '按住 说话' }}
              </button>
            </template>
            
            <template v-else>
              <div v-if="selectedImages.length > 0" class="flex flex-wrap gap-2 p-3 bg-gray-50 dark:bg-gray-600 border-b border-gray-100 dark:border-gray-700">
                <div v-for="(img, idx) in selectedImages" :key="idx" class="relative group">
                  <img :src="img" class="w-16 h-16 object-cover rounded-md border border-gray-200 dark:border-gray-500" />
                  <button @click="removeImage(idx)" class="absolute -top-2 -right-2 bg-gray-800 text-white rounded-full w-5 h-5 flex items-center justify-center text-xs opacity-0 group-hover:opacity-100 transition shadow-sm">x</button>
                </div>
              </div>
              <div class="flex items-end relative">
                <input type="file" ref="fileInput" @change="handleImageUpload" accept="image/*" multiple class="hidden" />
                <button @click="$refs.fileInput.click()" class="h-10 w-10 flex items-center justify-center text-gray-400 hover:text-blue-500 dark:hover:text-blue-400 transition mb-[8px] ml-2 shrink-0">
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                  </svg>
                </button>
                <textarea
                  v-model="inputRaw"
                  @keydown.enter.exact.prevent="sendMessage"
                  class="w-full max-h-48 min-h-[56px] py-4 pl-2 pr-12 bg-transparent border-none focus:ring-0 resize-none outline-none text-sm"
                  placeholder="发送消息... (Shift + Enter 换行)"
                  rows="1"
                ></textarea>
                <button
                  @click="sendMessage"
                  :disabled="loading || (!inputRaw.trim() && selectedImages.length === 0)"
                  class="absolute right-2 bottom-3 w-8 h-8 flex items-center justify-center rounded-lg text-white transition disabled:opacity-50"
                  :class="(inputRaw.trim() || selectedImages.length > 0) && !loading ? 'bg-blue-500 hover:bg-blue-600' : 'bg-gray-300 dark:bg-gray-600'"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                    <path d="M10.894 2.553a1 1 0 00-1.788 0l-7 14a1 1 0 001.169 1.409l5-1.429A1 1 0 009 15.571V11a1 1 0 112 0v4.571a1 1 0 00.725.962l5 1.428a1 1 0 001.17-1.408l-7-14z" />
                  </svg>
                </button>
              </div>
            </template>
            
          </div>
        </div>
      </div>
    </div>
  </div>
  
  <SetupWizard
    v-if="showSetupWizard"
    :initial-settings="settings"
    @done="onSetupComplete"
  />

  <SettingsModal 
    :show="showSettings"
    :initial-settings="settings"
    :user-id="user?.id || null"
    :current-role-id="selectedRole?.id || null"
    :is-electron="isElectron"
    @close="showSettings = false"
    @save="handleSettingsSave"
    ref="settingsModal"
  />

  <!-- 角色选择器模态框 -->
  <div v-if="showRoleSelector" class="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
    <div class="bg-white dark:bg-gray-800 rounded-xl shadow-lg w-full max-w-4xl mx-4 max-h-[80vh] overflow-hidden">
      <RoleCardSelector 
        :roles="roles"
        :selected-role-id="selectedRole?.id || null"
        @select="(role) => { selectedRole = role; showRoleSelector = false; showMobileSidebar = false; onRoleChange(); }"
        @close="showRoleSelector = false"
      />
    </div>
  </div>

  <!-- Live2D 黍模型 -->
  <Live2DCanvas />
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import SettingsModal from './components/SettingsModal.vue'
import RoleCardSelector from './components/RoleCardSelector.vue'
import Live2DCanvas from './components/Live2DCanvas.vue'
import { live2dController } from './live2d/live2d-controller.js'
import { DEFAULT_SETTINGS, fetchRuntimeConfig, runtimeConfigToSettings } from './utils/runtimeConfig.js'
import { apiFetch, apiUrl, initApiBase } from './utils/api.js'
import SetupWizard from './components/SetupWizard.vue'

const inputRaw = ref('')
const messages = ref([])
const loading = ref(false)

// 联网搜索：默认关，由用户手动 toggle（开启时强制走一次 Search-RAG 并注入；关闭时模型仍可自行调用 webSearch 工具）
const useSearch = ref(false)

const toggleSearch = () => {
  useSearch.value = !useSearch.value
}

const fileInput = ref(null)
const selectedImages = ref([])

const handleImageUpload = (e) => {
  const files = Array.from(e.target.files)
  if (!files.length) return
  if (selectedImages.value.length + files.length > 5) {
    ElMessage.warning(`最多只能上传 5 张图片`);
    return
  }
  files.forEach(file => {
    if (file.size > 5 * 1024 * 1024) {
      ElMessage.warning(`图片 ${file.name} 超过 5MB 限制`);
      return
    }
    const reader = new FileReader()
    reader.onload = (ev) => {
      selectedImages.value.push(ev.target.result)
    }
    reader.readAsDataURL(file)
  })
  if (fileInput.value) fileInput.value.value = ''
}

const removeImage = (index) => {
  selectedImages.value.splice(index, 1)
}

const user = ref(null)
const loginForm = ref({ username: '', password: '' })
const isLoggingIn = ref(false)
const loginError = ref('')

const roles = ref([])
const selectedRole = ref(null)

const findDefaultRole = (roleList) => {
  if (!roleList?.length) return null
  return roleList.find(r => r.roleCode === 'shu')
    || roleList.find(r => r.name === '黍')
    || roleList[0]
}

const conversations = ref([])
const currentConversationId = ref('')

const isVoiceMode = ref(false)
const isRecording = ref(false)
let mediaRecorder = null
let audioChunks = []

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

// 设置相关
const showSettings = ref(false)
const showSetupWizard = ref(false)
const isElectron = !!window.electronAPI
const showRoleSelector = ref(false)
const showMobileSidebar = ref(false)
const settings = ref({ ...DEFAULT_SETTINGS })

const doLogin = async () => {
  if (!loginForm.value.username || !loginForm.value.password) {
    loginError.value = '请输入用户名和密码'
    return
  }
  isLoggingIn.value = true
  loginError.value = ''
  try {
    const res = await apiFetch('/api/user/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(loginForm.value)
    })
    const data = await res.json()
    if (data.success) {
      user.value = { id: data.userId, username: data.username }
      localStorage.setItem('chat_user', JSON.stringify(user.value))
      await initDataAfterLogin()
    } else {
      loginError.value = data.message || '登录失败'
    }
  } catch (e) {
    loginError.value = '网络错误'
  } finally {
    isLoggingIn.value = false
  }
}

const logout = () => {
  unregisterProactiveChat()
  user.value = null
  localStorage.removeItem('chat_user')
  messages.value = []
  conversations.value = []
  selectedRole.value = null
}

const loadRoles = async () => {
  const maxAttempts = 12
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const res = await apiFetch('/api/roles')
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      if (!Array.isArray(data)) throw new Error('角色列表格式错误')
      roles.value = data
      if (roles.value.length > 0 && !selectedRole.value) {
        selectedRole.value = findDefaultRole(roles.value)
      }
      return
    } catch (e) {
      if (attempt === maxAttempts) {
        console.error('加载角色失败', e)
        return
      }
      await new Promise(resolve => setTimeout(resolve, 1000))
    }
  }
}

const onRoleChange = async () => {
  // 角色切换时，重新加载该角色的对话列表
  await loadConversations()
  if (conversations.value.length > 0) {
    await selectConversation(conversations.value[0].id)
  } else {
    newChat()
  }
}

const loadConversations = async () => {
  if (!user.value || !selectedRole.value) return
  try {
    const res = await apiFetch(`/api/conversation/user/${user.value.id}/role/${selectedRole.value.id}`)
    const data = await res.json()
    conversations.value = data || []
  } catch (e) {
    console.error('加载对话列表失败', e)
  }
}

const selectConversation = async (id) => {
  showMobileSidebar.value = false
  if (loading.value) await abortCurrentChat()
  else stopCurrentAudio()
  chatRequestSeq++
  loading.value = false
  // 切换会话前先注销旧的 proactive
  await unregisterProactiveChat()
  currentConversationId.value = id
  try {
    const [res, proactiveRes] = await Promise.all([
      apiFetch(`/api/conversation/${id}/history`),
      apiFetch(`/api/proactive-research/conversation/${id}`)
    ])
    const data = await res.json()
    const candidates = proactiveRes.ok ? await proactiveRes.json() : []
    const candidateByResponse = new Map((candidates || []).filter(c => c.responseText).map(c => [c.responseText, c]))
    // 后端 History 字段映射: sender → role, content → content
    messages.value = (data || [])
      .filter(h => !(h.sender === 'user' && String(h.content || '').startsWith('[System: 用户')))
      .map(h => {
        const candidate = h.sender === 'assistant' ? candidateByResponse.get(h.content || '') : null
        let sources = []
        if (candidate?.sourcesJson) {
          try { sources = JSON.parse(candidate.sourcesJson) } catch {}
        }
        return {
          role: h.sender === 'assistant' ? 'ai' : h.sender,
          content: h.content || '',
          isProactive: !!candidate,
          candidateId: candidate?.id || null,
          sources,
          feedback: candidate?.feedback || null
        }
      })
    scrollToBottom()
  } catch (e) {
    console.error('加载对话消息失败', e)
    messages.value = []
  }
  // 切换后重新注册 proactive
  registerProactiveChat()
}

const newChat = async () => {
  showMobileSidebar.value = false
  if (loading.value) await abortCurrentChat()
  else stopCurrentAudio()
  chatRequestSeq++
  loading.value = false
  await unregisterProactiveChat()
  currentConversationId.value = `conv_${Date.now()}`
  messages.value = []
  registerProactiveChat()
}

const deleteConversation = async (id) => {
  try {
    await ElMessageBox.confirm('确定要删除这个对话吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await apiFetch(`/api/conversation/${id}`, { method: 'DELETE' })
    conversations.value = conversations.value.filter(c => c.id !== id)
    if (currentConversationId.value === id) {
      newChat()
    }
    ElMessage.success('删除成功')
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

onMounted(async () => {
  await initApiBase()
  const savedUser = localStorage.getItem('chat_user')
  if (savedUser) {
    try {
      user.value = JSON.parse(savedUser)
      initDataAfterLogin()
    } catch (e) {
      console.error('解析用户信息失败', e)
    }
  }
  // 从 localStorage 恢复设置，重启后自动生效
  restoreSettings()
  // 根据深色模式设置应用样式
  applyDarkMode(settings.value.darkMode)
  document.addEventListener('mousedown', unlockAudioSync)
  document.addEventListener('touchstart', unlockAudioSync, true)
  document.addEventListener('mousedown', globalUnlock)
  document.addEventListener('touchstart', globalUnlock, true)

  // 监听 Live2D 子窗口的点击互动事件，触发主动说话
  if (window.electronAPI && window.electronAPI.onLive2dInteract) {
    window.electronAPI.onLive2dInteract(() => {
      if (!settings.value.proactiveChatEnabled) return
      triggerProactiveFromInteract()
    })
  }
  if (window.electronAPI?.onShowSetupWizard) {
    window.electronAPI.onShowSetupWizard(() => {
      showSetupWizard.value = true
    })
  }
  if (window.electronAPI?.isFirstRun) {
    window.electronAPI.isFirstRun().then((first) => {
      if (first) showSetupWizard.value = true
    })
  }
})

const scrollToBottom = async () => {
  await nextTick()
  await nextTick()
  const container = document.getElementById('chat-container')
  if (container) {
    container.scrollTop = container.scrollHeight
  }
}

const sendMessage = async (e) => {
  if (e && e.shiftKey) return
  const text = inputRaw.value.trim()
  const hasImages = selectedImages.value.length > 0
  if ((!text && !hasImages)) return

  // 如果正在生成，先打断当前对话
  if (loading.value) {
    await abortCurrentChat()
    loading.value = false
  } else {
    // 主动搭话的 SSE 不会设置 loading，但它的语音同样必须被用户输入立即打断。
    stopCurrentAudio()
  }

  const currentImages = [...selectedImages.value]
  inputRaw.value = ''
  selectedImages.value = []
  
  messages.value.push({ role: 'user', content: text, images: currentImages })
  scrollToBottom()

  const aiMsgIndex = messages.value.length
  messages.value.push({ role: 'ai', content: '' })
  const requestSeq = ++chatRequestSeq
  loading.value = true

  const requestBody = {
    inputMode: 'text',
    userId: String(user.value.id),
    conversationId: currentConversationId.value,
    message: text,
    images: currentImages.length > 0 ? currentImages : null,
    stream: true,
    search: useSearch.value,
    rag: true,                       // RAG 默认常开
    tools: true,                     // 本地 MCP 工具默认常开
    roleId: selectedRole.value ? selectedRole.value.id : 1,
    // TTS 回播：只要角色配置了 voiceId 就附带（文字/语音输入都走 TTS）
    ...(selectedRole.value?.voiceId ? {
      ttsVoiceId: selectedRole.value.voiceId,
      ttsSpeedFactor: settings.value.ttsSpeed || 1.0,
      ttsPitchFactor: settings.value.ttsPitch || 1.0
    } : {})
  }

  try {
    await doChatSSE(requestBody, null, aiMsgIndex)
    if (messages.value.length <= 3) await loadConversations()
  } catch (error) {
    console.error(error)
    if (!messages.value[aiMsgIndex].content) {
      messages.value[aiMsgIndex].content = '[Error: 请求失败]'
    }
  } finally {
    if (chatRequestSeq === requestSeq) loading.value = false
  }
}

// Audio recording
const startRecording = async () => {
  if (isRecording.value || loading.value) return
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    mediaRecorder = new MediaRecorder(stream)
    audioChunks = []
    mediaRecorder.ondataavailable = e => {
      if (e.data.size > 0) audioChunks.push(e.data)
    }
    mediaRecorder.onstop = () => {
      const audioBlob = new Blob(audioChunks, { type: 'audio/webm' })
      sendAudio(audioBlob)
      stream.getTracks().forEach(track => track.stop())
    }
    mediaRecorder.start()
    isRecording.value = true
  } catch (e) {
    ElMessage.error('无法获取麦克风权限')
  }
}

const stopRecording = () => {
  if (isRecording.value && mediaRecorder) {
    mediaRecorder.stop()
    isRecording.value = false
  }
}

const toggleVoiceMode = () => {
  isVoiceMode.value = !isVoiceMode.value
}

// Audio playback
const audioQueue = []
let audioPlaying = false
const audioUnlocked = ref(false)  // AudioContext 是否已 resume
let audioCtx = null               // 全局唯一 AudioContext
let currentSource = null          // 正在播放的 BufferSource（用于停止）
const ttsBuffers = new Map()      // playbackKey -> { chunks: Uint8Array[] }
/** 流式 PCM：按 AudioContext 时间轴无缝拼接，避免 8KB 分片断档/爆音 */
const ttsStreamPlayers = new Map() // playbackKey -> { nextTime, carry, ... }
let currentAbortController = null  // 当前 SSE 请求的 AbortController（用于打断）
let chatRequestSeq = 0
let ttsPlaybackSessionSeq = 0
let ttsPlaybackGeneration = 0

/**
 * 每条 SSE 回复都有独立播放会话。后端的 idx 每次回复都会从 0 开始，
 * 所以播放器内部不能直接拿 idx 当全局 key，否则普通回复和主动搭话会串流/重叠。
 */
const createTtsPlaybackSession = (latencySession = null) => ({
  id: ++ttsPlaybackSessionSeq,
  generation: ttsPlaybackGeneration,
  latencySession,
})

const isTtsPlaybackSessionActive = (session) => (
  !!session && session.generation === ttsPlaybackGeneration
)

const ttsPlaybackKey = (session, idx) => `${session.id}:${idx}`

/**
 * 获取/创建全局 AudioContext。
 * 注意：AudioContext 构造必须在用户手势同一帧，不能延迟。
 */
const getAudioCtx = () => {
  if (audioCtx) return audioCtx
  const Ctor = window.AudioContext || window.webkitAudioContext
  if (!Ctor) return null
  try {
    audioCtx = new Ctor()
    return audioCtx
  } catch (e) {
    console.warn('AudioContext 构造失败', e)
    return null
  }
}

/**
 * 在用户手势中 resume AudioContext。幂等，同步调用。
 * 只要 resume 过一次，整个页面生命周期内 decodeAudioData + start 都无限制。
 */
const unlockAudioSync = () => {
  const ctx = getAudioCtx()
  if (!ctx) return
  if (ctx.state === 'suspended') {
    try {
      ctx.resume()
      audioUnlocked.value = true
    } catch (e) {
      console.warn('AudioContext resume 失败', e)
    }
  } else {
    audioUnlocked.value = true
  }
}

const globalUnlock = () => {
  unlockAudioSync()
}

/**
 * 用户主动点击"启用语音"按钮时调用：
 * 确保 AudioContext 已创建并 resume，同时标记为已解锁。
 */
const forceEnableAudio = () => {
  unlockAudioSync()
  if (!audioUnlocked.value) {
    // 如果还是没解锁，尝试创建一个静音 AudioBuffer 播放来强制激活
    const ctx = getAudioCtx()
    if (ctx && ctx.state === 'suspended') {
      ctx.resume().then(() => {
        audioUnlocked.value = true
      }).catch(() => {})
    }
  }
}

const decodeTtsChunkBytes = (audioBase64) => {
  const bin = atob(audioBase64)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return bytes
}

const enqueueTtsAudio = (key, idx, pcm, buf, session) => {
  audioQueue.push({
    key,
    idx,
    pcm,
    sampleRate: buf.sampleRate,
    channels: buf.channels,
    format: buf.format,
    session,
  })
  pumpAudioQueue()
}

const stopTtsStreamPlayer = (key) => {
  const player = ttsStreamPlayers.get(key)
  if (!player) return
  if (player.stopTimer) clearTimeout(player.stopTimer)
  for (const src of player.activeSources) {
    try { src.stop() } catch (e) { /* already stopped */ }
  }
  ttsStreamPlayers.delete(key)
}

/**
 * 流式 PCM：在 AudioContext 时间轴上连续 schedule，保证样本边界对齐。
 * 不可把每个 HTTP/SSE 分片当成独立 BufferSource 排队（会断档，且奇数字节会爆音）。
 */
const feedStreamPcm = (key, bytes, buf, session) => {
  if (!isTtsPlaybackSessionActive(session)) return
  const ctx = getAudioCtx()
  if (!ctx) return
  if (ctx.state !== 'running') return

  const isF32 = buf.format === 'pcm_f32le'
  const alignBytes = isF32 ? 4 : 2  // float32=4字节对齐, int16=2字节对齐

  let player = ttsStreamPlayers.get(key)
  if (!player) {
    player = {
      sampleRate: buf.sampleRate,
      channels: buf.channels || 1,
      format: buf.format,
      nextTime: ctx.currentTime + 0.02,
      carryLen: 0,      // carry 中未对齐的字节数
      carry: null,       // Uint8Array，存放上一次未对齐的尾部字节
      lipSyncStarted: false,
      activeSources: [],
      stopTimer: null,
      session,
    }
    ttsStreamPlayers.set(key, player)
  }

  // 拼接 carry + 新字节
  let merged = bytes
  if (player.carry !== null && player.carryLen > 0) {
    const joined = new Uint8Array(player.carryLen + bytes.length)
    joined.set(player.carry.subarray(0, player.carryLen), 0)
    joined.set(bytes, player.carryLen)
    merged = joined
    player.carry = null
    player.carryLen = 0
  }

  // 按对齐字节截断
  const alignedLen = merged.length & ~(alignBytes - 1)
  if (alignedLen < alignBytes) {
    // 全部不够一个样本，拷贝到独立 buffer 暂存
    const carryBuf = new Uint8Array(merged.length)
    carryBuf.set(merged.subarray ? merged : new Uint8Array(merged), 0)
    player.carry = carryBuf
    player.carryLen = merged.length
    return
  }

  // 尾部未对齐部分暂存（必须拷贝到独立 buffer，避免 view 引用被后续覆盖）
  if (alignedLen < merged.length) {
    const carryBytes = merged.length - alignedLen
    const carryBuf = new Uint8Array(carryBytes)
    carryBuf.set(merged.subarray(alignedLen, merged.length), 0)
    player.carry = carryBuf
    player.carryLen = carryBytes
  }

  const pcm = merged.subarray(0, alignedLen)
  const audioBuf = isF32
    ? pcmFloat32ToAudioBuffer(ctx, pcm, player.sampleRate, player.channels)
    : pcmInt16ToAudioBuffer(ctx, pcm, player.sampleRate, player.channels)
  const src = ctx.createBufferSource()
  src.buffer = audioBuf
  src.connect(ctx.destination)

  if (player.nextTime < ctx.currentTime) {
    player.nextTime = ctx.currentTime
  }
  src.start(player.nextTime)
  player.nextTime += audioBuf.duration
  player.activeSources.push(src)

  if (!player.lipSyncStarted) {
    player.lipSyncStarted = true
    live2dController.startLipSync(ctx, src)
    const latencySession = session.latencySession
    if (latencySession && !latencySession.steps.client_first_tts_play) {
      markLatency(latencySession, 'client_first_tts_play')
    }
  }

  src.onended = () => {
    const i = player.activeSources.indexOf(src)
    if (i >= 0) player.activeSources.splice(i, 1)
  }
}

const finishTtsStreamPlayer = (key) => {
  const player = ttsStreamPlayers.get(key)
  if (!player) return
  if (player.carry !== null && player.carryLen > 0) {
    // 补零对齐后刷出剩余字节
    const alignBytes = (player.format === 'pcm_f32le') ? 4 : 2
    const padLen = alignBytes - (player.carryLen % alignBytes)
    if (padLen < alignBytes) {
      const padded = new Uint8Array(player.carryLen + padLen)
      padded.set(player.carry.subarray(0, player.carryLen), 0)
      feedStreamPcm(key, padded, {
        sampleRate: player.sampleRate,
        channels: player.channels,
        format: player.format,
      }, player.session)
    }
    player.carry = null
    player.carryLen = 0
  }
  const ctx = getAudioCtx()
  const delayMs = ctx
    ? Math.max(50, (player.nextTime - ctx.currentTime) * 1000 + 50)
    : 50
  player.stopTimer = setTimeout(() => {
    if (ttsStreamPlayers.get(key) === player) {
      live2dController.stopLipSync()
      ttsStreamPlayers.delete(key)
      // 音频实际播完，通知串行队列推进下一句
      onTtsSentenceFinished(key, player.session)
    }
  }, delayMs)
}

// ---- TTS 句子级串行播放队列 ----
// 同一对话中多个句子的流式 PCM 播放器必须串行排队，
// 前一句播放结束后再开始下一句，避免多句重叠。
const ttsSentenceQueue = []        // 排队等待播放的 playbackKey
let ttsPlayingIdx = null           // 当前正在播放的 playbackKey
const ttsSentenceReady = new Set() // 已收到 chunkEnd 但还没开始播放的 playbackKey

/**
 * 将句子 idx 加入串行队列，若当前无句子在播放则立即开始。
 */
const enqueueTtsSentence = (key) => {
  if (ttsSentenceQueue.includes(key) || ttsPlayingIdx === key) return
  ttsSentenceQueue.push(key)
  pumpTtsSentenceQueue()
}

/**
 * 驱动 TTS 句子串行队列：当前无播放时，取出队首 idx 并激活其 streamPlayer。
 */
const pumpTtsSentenceQueue = () => {
  if (ttsPlayingIdx !== null) return  // 正在播放，等前一句结束
  if (ttsSentenceQueue.length === 0) return
  ttsPlayingIdx = ttsSentenceQueue.shift()

  // 激活该句子的 streamPlayer：把暂存的 chunks 灌入 feedStreamPcm
  const pending = ttsPendingChunks.get(ttsPlayingIdx)
  if (pending) {
    ttsPendingChunks.delete(ttsPlayingIdx)
    for (const { bytes, buf, session } of pending) {
      feedStreamPcm(ttsPlayingIdx, bytes, buf, session)
    }
  }

  // 如果该句的所有 chunks 已经到齐（chunkEnd 已到），立即结束播放
  if (ttsSentenceReady.has(ttsPlayingIdx)) {
    ttsSentenceReady.delete(ttsPlayingIdx)
    const key = ttsPlayingIdx
    const player = ttsStreamPlayers.get(key)
    if (player) finishTtsStreamPlayer(key)
    else onTtsSentenceFinished(key, pending?.[0]?.session)
    // 注意：finishTtsStreamPlayer 内部的 setTimeout 会在音频播完后
    // 调用 onTtsSentenceFinished 推进下一句
  }

  // 非流式格式可能已经在完整音频队列中等候。
  pumpAudioQueue()
}

/**
 * 当前句子的音频实际播完后调用，推进队列中的下一句。
 * 注意：不能在 chunkEnd 时同步调用，因为此时音频还在 AudioContext 时间轴上播放。
 * 必须等到 player.nextTime 到期（即最后一个 BufferSource 播完）后再推进。
 */
const onTtsSentenceFinished = (key, playbackSession) => {
  if (ttsPlayingIdx === key) {
    ttsPlayingIdx = null
    pumpTtsSentenceQueue()
  }
  const session = playbackSession?.latencySession
  if (session) {
    session.playingTts.delete(key)
    markLatency(session, 'client_last_tts_play')
    tryFinishLatencySession(session)
  }
}

// 暂存因串行队列未到而延迟喂入的 PCM chunks：playbackKey -> [{bytes, buf, session}]
const ttsPendingChunks = new Map()

const handleTtsEvent = (payload, playbackSession) => {
  if (!isTtsPlaybackSessionActive(playbackSession)) return
  const idx = payload.idx
  if (idx === undefined || idx === null) return
  const key = ttsPlaybackKey(playbackSession, idx)
  if (payload.chunkStart) {
    // 同一回复内重复的 chunkStart 是重放/重连数据，不能再次入队。
    if (ttsBuffers.has(key) || ttsStreamPlayers.has(key)
        || ttsSentenceQueue.includes(key) || ttsPlayingIdx === key) return
    ttsBuffers.set(key, {
      sampleRate: payload.sampleRate || 48000,
      channels: payload.channels || 1,
      format: payload.format || 'pcm_s16le',
      streamPlay: !!payload.streamPlay,
      chunks: []
    })
    const latencySession = playbackSession.latencySession
    if (latencySession) {
      latencySession.ttsSentenceCount++
      latencySession.playingTts.add(key)
    }
    // 所有格式共用同一个句子队列；否则 WAV/完整 PCM 队列会和流式 PCM 同时播放。
    enqueueTtsSentence(key)
  }
  const buf = ttsBuffers.get(key)
  if (!buf) return
  if (payload.audioBase64) {
    try {
      const bytes = decodeTtsChunkBytes(payload.audioBase64)
      const latencySession = playbackSession.latencySession
      if (latencySession && !latencySession.steps.client_first_tts_chunk) {
        markLatency(latencySession, 'client_first_tts_chunk')
      }
      if (buf.streamPlay && (buf.format === 'pcm_s16le' || buf.format === 'pcm_f32le')) {
        // 串行控制：只有当前正在播放的句子才立即喂入 feedStreamPcm
        if (ttsPlayingIdx === key) {
          feedStreamPcm(key, bytes, buf, playbackSession)
        } else {
          // 还没轮到该句播放，暂存 chunks
          if (!ttsPendingChunks.has(key)) ttsPendingChunks.set(key, [])
          ttsPendingChunks.get(key).push({ bytes, buf, session: playbackSession })
        }
      } else {
        buf.chunks.push(bytes)
      }
    } catch (e) {
      console.error('audio chunk 解码失败', e)
    }
  }
  if (payload.chunkEnd) {
    if (buf.streamPlay) {
      // 如果该句暂存了 chunks 且当前正在播放它，一次性灌入
      const pending = ttsPendingChunks.get(key)
      if (pending && ttsPlayingIdx === key) {
        ttsPendingChunks.delete(key)
        for (const { bytes, buf: b, session } of pending) {
          feedStreamPcm(key, bytes, b, session)
        }
      }
      if (ttsPlayingIdx === key) {
        // 正在播放的句子 chunkEnd → 等音频播完后自动推进下一句
        const player = ttsStreamPlayers.get(key)
        if (player) finishTtsStreamPlayer(key)
        else onTtsSentenceFinished(key, playbackSession)
      } else {
        // 还没轮到播放的句子 chunkEnd → 标记已就绪，等轮到时再处理
        ttsSentenceReady.add(key)
      }
      ttsBuffers.delete(key)
      return
    }
    if (buf.chunks.length === 0) {
      ttsBuffers.delete(key)
      if (ttsPlayingIdx === key) onTtsSentenceFinished(key, playbackSession)
      else {
        const pos = ttsSentenceQueue.indexOf(key)
        if (pos >= 0) ttsSentenceQueue.splice(pos, 1)
      }
      return
    }
    let total = 0
    for (const c of buf.chunks) total += c.length
    const merged = new Uint8Array(total)
    let off = 0
    for (const c of buf.chunks) { merged.set(c, off); off += c.length }
    ttsBuffers.delete(key)
    enqueueTtsAudio(key, idx, merged, buf, playbackSession)
  }
}

/**
 * 把 IEEE float32 LE PCM 直接灌进 AudioBuffer（Astra TTS 引擎输出格式）。
 * float32 值域 [-1.0, 1.0]，直接写入 AudioBuffer 的 Float32Array。
 */
const pcmFloat32ToAudioBuffer = (ctx, pcmBytes, sampleRate, channels) => {
  const sampleCount = Math.floor(pcmBytes.length / 4 / channels)
  const audioBuf = ctx.createBuffer(channels, sampleCount, sampleRate)
  const view = new DataView(pcmBytes.buffer, pcmBytes.byteOffset, pcmBytes.byteLength)
  for (let ch = 0; ch < channels; ch++) {
    const channelData = audioBuf.getChannelData(ch)
    for (let i = 0; i < sampleCount; i++) {
      channelData[i] = view.getFloat32((i * channels + ch) * 4, true)
    }
  }
  return audioBuf
}

/**
 * 把 int16 LE 单声道 PCM 直接灌进 AudioBuffer，零解码开销。
 * 不再走 decodeAudioData（旧 GPT-SoVITS 的 streaming wav 多 RIFF header 会让它崩）。
 */
const pcmInt16ToAudioBuffer = (ctx, pcmBytes, sampleRate, channels) => {
  const sampleCount = Math.floor(pcmBytes.length / 2 / channels)
  const audioBuf = ctx.createBuffer(channels, sampleCount, sampleRate)
  // DataView 处理小端 int16
  const view = new DataView(pcmBytes.buffer, pcmBytes.byteOffset, pcmBytes.byteLength)
  for (let ch = 0; ch < channels; ch++) {
    const channelData = audioBuf.getChannelData(ch)
    for (let i = 0; i < sampleCount; i++) {
      const s16 = view.getInt16((i * channels + ch) * 2, true)
      channelData[i] = s16 / 32768
    }
  }
  return audioBuf
}

const pumpAudioQueue = () => {
  if (audioPlaying) return
  const ctx = getAudioCtx()
  if (!ctx) return
  if (ctx.state !== 'running') return
  let item = audioQueue[0]
  // 被打断的旧 SSE 即使晚到，也不能恢复播放。
  while (item && !isTtsPlaybackSessionActive(item.session)) {
    audioQueue.shift()
    item = audioQueue[0]
  }
  if (!item) return
  // 完整音频也必须等全局句子队列轮到自己，不能绕过流式播放器。
  if (ttsPlayingIdx !== item.key) return
  audioQueue.shift()
  audioPlaying = true
  try {
    // WAV 格式：走 decodeAudioData 解码
    // pcm_s16le 格式（Astra TTS 引擎）：直接灌 AudioBuffer，零解码开销
    const decodeAndPlay = (audioBuf) => {
      if (!isTtsPlaybackSessionActive(item.session)) {
        audioPlaying = false
        pumpAudioQueue()
        return
      }
      const src = ctx.createBufferSource()
      src.buffer = audioBuf
      src.connect(ctx.destination)
      currentSource = src
      // TTS 播放时启动 Live2D 口型同步
      live2dController.startLipSync(ctx, src)
      const latencySession = item.session?.latencySession
      if (latencySession && !latencySession.steps.client_first_tts_play) {
        markLatency(latencySession, 'client_first_tts_play')
      }
      src.onended = () => {
        currentSource = null
        audioPlaying = false
        live2dController.stopLipSync()
        onTtsSentenceFinished(item.key, item.session)
        pumpAudioQueue()
      }
      src.start(0)
    }

    if (item.format === 'wav') {
      // 完整 WAV 文件，用 decodeAudioData 解码
      ctx.decodeAudioData(item.pcm.buffer.slice(item.pcm.byteOffset, item.pcm.byteOffset + item.pcm.byteLength))
        .then(decodeAndPlay)
        .catch(e => {
          console.warn('WAV 解码失败 idx=' + item.idx + ':', e && e.message)
          audioPlaying = false
          live2dController.stopLipSync()
          onTtsSentenceFinished(item.key, item.session)
          pumpAudioQueue()
        })
    } else {
      // raw PCM (pcm_s16le / pcm_f32le)：直接灌 AudioBuffer
      const audioBuf = item.format === 'pcm_f32le'
        ? pcmFloat32ToAudioBuffer(ctx, item.pcm, item.sampleRate, item.channels)
        : pcmInt16ToAudioBuffer(ctx, item.pcm, item.sampleRate, item.channels)
      decodeAndPlay(audioBuf)
    }
  } catch (e) {
    console.warn('PCM 播放失败 idx=' + item.idx + ':', e && e.message)
    audioPlaying = false
    live2dController.stopLipSync()
    onTtsSentenceFinished(item.key, item.session)
    pumpAudioQueue()
  }
}


/**
 * 停止当前 TTS 音频播放：停止 source + 清空 audioQueue + 重置 audioPlaying
 */
const stopCurrentAudio = () => {
  // 先使所有已经分发出去的旧 SSE 播放会话失效，防止 stop 之后的晚到 chunk 重新出声。
  ttsPlaybackGeneration++
  if (currentSource) {
    try { currentSource.stop() } catch (e) { /* already stopped */ }
    currentSource = null
  }
  audioPlaying = false
  audioQueue.length = 0
  ttsBuffers.clear()
  for (const idx of [...ttsStreamPlayers.keys()]) {
    stopTtsStreamPlayer(idx)
  }
  // 清理串行播放队列
  ttsSentenceQueue.length = 0
  ttsPlayingIdx = null
  ttsPendingChunks.clear()
  ttsSentenceReady.clear()
  live2dController.stopLipSync()
}

/**
 * 打断当前正在进行的聊天（SSE 流 + TTS 播放）：
 * ① 立即取消当前 fetch 并清空/停止本地音频
 * ② 调后端 /api/chat/interrupt 停止 LLM/TTS 生成
 * ③ 清理当前 AI 消息气泡（保留已收到文本或标记 [已打断]）
 */
const abortCurrentChat = async () => {
  // 1. 本地必须同步静音，不能等待后端 interrupt 的网络往返。
  if (currentAbortController) {
    currentAbortController.abort()
    currentAbortController = null
  }
  stopCurrentAudio()

  // 2. 再通知后端停止生成，避免本地等待期间旧语音继续播放。
  if (currentConversationId.value) {
    try {
      await apiFetch('/api/chat/interrupt', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ conversationId: currentConversationId.value })
      })
    } catch (e) {
      console.warn('interrupt 请求失败', e)
    }
  }

  // 3. 标记当前 AI 消息为 [已打断]（仅当内容为空时）
  const lastMsg = messages.value[messages.value.length - 1]
  if (lastMsg && lastMsg.role === 'ai' && !lastMsg.content) {
    lastMsg.content = '[已打断]'
  }
}

// ============================================================
// 全链路延迟追踪（打字 → SSE → TTS 播放完）
// ============================================================
let currentLatencySession = null

const markLatency = (session, step) => {
  if (!session || session.steps[step]) return
  session.steps[step] = Date.now()
}

const reportLatency = async (session) => {
  if (!session?.traceId || session.reported) return
  session.reported = true
  try {
    await apiFetch('/api/chat/latency', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        traceId: session.traceId,
        conversationId: currentConversationId.value,
        steps: session.steps
      })
    })
  } catch (e) {
    console.warn('latency report failed', e)
  }
}

const tryFinishLatencySession = (session) => {
  if (!session || session.reported || !session.sseDone) return
  if (session.wantTts && session.ttsSentenceCount > 0 && session.playingTts.size > 0) return
  markLatency(session, 'client_sse_done')
  if (!session.wantTts || session.ttsSentenceCount === 0) {
    markLatency(session, 'client_last_tts_play')
  }
  reportLatency(session)
}

const beginLatencySession = (requestBody) => {
  const clientSentAt = Date.now()
  requestBody.clientSentAt = clientSentAt
  const session = {
    traceId: null,
    wantTts: !!requestBody.ttsVoiceId,
    sseDone: false,
    reported: false,
    ttsSentenceCount: 0,
    playingTts: new Set(),
    steps: {
      client_sent: clientSentAt,
      client_fetch_start: Date.now()
    }
  }
  currentLatencySession = session
  return session
}

// ============================================================
// 流式对话（统一入口）：语音/文本都走 POST /api/chat
// SSE 事件：asr / text / tts / done / error
// ============================================================

/**
 * 公共 SSE 流式对话处理
 * @param requestBody ChatRequest JSON body
 * @param userMsgIndex 用户消息在 messages 数组中的 index（语音输入时需要 ASR 回填，传 null 则跳过）
 * @param aiMsgIndex AI 消息在 messages 数组中的 index
 */
const doChatSSE = async (requestBody, userMsgIndex, aiMsgIndex) => {
  // 用户主动发消息：保留表情/动作/口型，但不播放 Live2D 动作音效
  live2dController.setMotionSoundEnabled(false)
  const latencySession = beginLatencySession(requestBody)
  const playbackSession = createTtsPlaybackSession(latencySession)
  // 创建 AbortController 支持请求级打断
  const controller = new AbortController()
  currentAbortController = controller

  const response = await apiFetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(requestBody),
    signal: controller.signal
  })
  markLatency(latencySession, 'client_response_headers')
  if (!response.ok || !response.body) throw new Error('HTTP error')

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let firstSse = true
  let firstText = true
  try {
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split(/\r?\n\r?\n/)
    buffer = events.pop() || ''
    for (const ev of events) {
      if (!ev.trim()) continue
      // 解析 SSE: event: <name>  + data: <json>
      let evName = 'message'
      const dataLines = []
      for (const line of ev.split(/\r?\n/)) {
        if (line.startsWith('event:')) evName = line.substring(6).trim()
        else if (line.startsWith('data:')) {
          let t = line.substring(5)
          if (t.startsWith(' ')) t = t.substring(1)
          dataLines.push(t)
        }
      }
      if (!dataLines.length) continue
      if (firstSse) {
        markLatency(latencySession, 'client_first_sse')
        firstSse = false
      }
      let payload = {}
      try { payload = JSON.parse(dataLines.join('\n')) } catch { payload = {} }

      if (evName === 'asr' && userMsgIndex !== null) {
        markLatency(latencySession, 'client_asr_text')
        // 语音输入：ASR 回填用户消息
        messages.value[userMsgIndex].content = payload.text || ''
        messages.value[userMsgIndex].isAudio = false
      } else if (evName === 'emotion') {
        live2dController.triggerEmotion(payload.emotion)
      } else if (evName === 'search_status') {
        messages.value[aiMsgIndex].searchStatus = payload.stage === 'complete' ? '' : (payload.message || '正在联网检索')
        if (Array.isArray(payload.sources) && payload.sources.length) {
          messages.value[aiMsgIndex].sources = payload.sources
        }
        scrollToBottom()
      } else if (evName === 'text') {
        if (firstText && (payload.delta || '')) {
          markLatency(latencySession, 'client_first_text')
          firstText = false
        }
        messages.value[aiMsgIndex].content += (payload.delta || '')
        scrollToBottom()
      } else if (evName === 'tts') {
        handleTtsEvent(payload, playbackSession)
      } else if (evName === 'error') {
        messages.value[aiMsgIndex].content += `\n[错误] ${payload.message || ''}`
      } else if (evName === 'done') {
        if (payload.traceId) latencySession.traceId = payload.traceId
        latencySession.sseDone = true
        tryFinishLatencySession(latencySession)
        live2dController.onConversationEnd()
      }
    }
  }
  } catch (e) {
    if (e.name === 'AbortError') {
      // 被 abortCurrentChat 打断，优雅退出
      console.log('SSE 流被打断')
      return
    }
    throw e
  } finally {
    // 旧请求的 finally 不能清掉后发请求的 AbortController。
    if (currentAbortController === controller) currentAbortController = null
  }
}

const sendAudio = async (audioBlob) => {
  // 如果正在生成，先打断当前对话
  if (loading.value) {
    await abortCurrentChat()
    loading.value = false
  } else {
    stopCurrentAudio()
  }
  const requestSeq = ++chatRequestSeq
  loading.value = true

  // 占位：用户气泡（ASR 文本回填后替换）
  const userMsgIndex = messages.value.length
  messages.value.push({ role: 'user', content: '🎙️ 识别中...', isAudio: true })
  const aiMsgIndex = userMsgIndex + 1
  messages.value.push({ role: 'ai', content: '' })
  scrollToBottom()

  // 将 audioBlob 转为 base64
  const audioBase64 = await blobToBase64(audioBlob)

  const requestBody = {
    inputMode: 'audio',
    userId: String(user.value.id),
    conversationId: currentConversationId.value,
    audioBase64: audioBase64,
    audioFormat: 'webm',
    roleId: selectedRole.value ? selectedRole.value.id : 1,
    search: useSearch.value,
    rag: true,
    tools: true,
    asrLanguage: settings.value.asrLanguage || 'zh',
    asrHotwords: '',
    ttsVoiceId: selectedRole.value?.voiceId || '',
    ttsSpeedFactor: settings.value.ttsSpeed || 1.0,
    ttsPitchFactor: settings.value.ttsPitch || 1.0
  }

  try {
    await doChatSSE(requestBody, userMsgIndex, aiMsgIndex)
    if (messages.value.length <= 3) await loadConversations()
  } catch (error) {
    console.error(error)
    if (!messages.value[aiMsgIndex].content) {
      messages.value[aiMsgIndex].content = '[Error: 语音流式对话失败]'
    }
  } finally {
    if (chatRequestSeq === requestSeq) loading.value = false
  }
}

/**
 * Blob 转 base64 字符串（不含 data:xxx;base64, 前缀）
 */
const blobToBase64 = (blob) => {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onloadend = () => {
      const dataUrl = reader.result
      // 去掉 "data:audio/webm;base64," 前缀
      const base64 = dataUrl.split(',')[1]
      resolve(base64)
    }
    reader.onerror = reject
    reader.readAsDataURL(blob)
  })
}

const playAudio = (url) => {
  const audio = new Audio(url)
  audio.play().catch(e => console.error('播音失败', e))
}

// 设置相关方法
const openSettings = () => {
  showMobileSidebar.value = false
  showSettings.value = true
}

const onSetupComplete = (saved) => {
  settings.value = { ...settings.value, ...saved }
  showSetupWizard.value = false
  localStorage.setItem('appSettings', JSON.stringify(settings.value))
}

/** 从 localStorage 恢复客户端缓存（后端不可用时兜底） */
const restoreSettings = () => {
  const saved = localStorage.getItem('appSettings')
  if (saved) {
    try {
      const parsed = JSON.parse(saved)
      settings.value = { ...DEFAULT_SETTINGS, ...parsed }
      settings.value.proactiveIdleSeconds = normalizeProactiveIdleSeconds(settings.value.proactiveIdleSeconds)
    } catch (e) {
      console.error('恢复设置失败', e)
    }
  }
}

/** 应用深色模式：给 <html> 添加/移除 dark class（配合 Tailwind dark: 前缀） */
const applyDarkMode = (isDark) => {
  if (isDark) {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }
}

const handleSettingsSave = async (newSettings) => {
  settings.value = { ...DEFAULT_SETTINGS, ...newSettings }
  settings.value.proactiveIdleSeconds = normalizeProactiveIdleSeconds(settings.value.proactiveIdleSeconds)
  localStorage.setItem('appSettings', JSON.stringify(settings.value))
  applyDarkMode(settings.value.darkMode)
  if (currentConversationId.value && user.value) {
    if (settings.value.proactiveChatEnabled) {
      registerProactiveChat()
    } else {
      unregisterProactiveChat()
    }
  }
}

// 加载设置（优先后端 runtime-config，fallback localStorage）
const loadSettings = async () => {
  restoreSettings()
  try {
    const config = await fetchRuntimeConfig()
    settings.value = { ...DEFAULT_SETTINGS, ...runtimeConfigToSettings(config) }
    settings.value.proactiveIdleSeconds = normalizeProactiveIdleSeconds(settings.value.proactiveIdleSeconds)
    localStorage.setItem('appSettings', JSON.stringify(settings.value))
    applyDarkMode(settings.value.darkMode)
  } catch (e) {
    console.warn('从后端加载配置失败，使用本地缓存', e)
  }
}

// 在登录后初始化设置
const initDataAfterLogin = async () => {
  await loadSettings()
  await loadRoles()
  // loadConversations 依赖 selectedRole，先确保角色已选中
  if (!selectedRole.value && roles.value.length > 0) {
    selectedRole.value = findDefaultRole(roles.value)
  }
  await loadConversations()
  if (conversations.value.length > 0) {
    await selectConversation(conversations.value[0].id)
  } else {
    newChat()
  }
}

// ============================================================
// 主动搭话（Proactive Chat）
// ============================================================

let proactiveEventSource = null  // SSE 长连接引用
let proactiveTtsPlaybackSession = null

/**
 * 注册主动搭话：调后端注册 + 开启 SSE 长连接监听
 */

/**
 * 点击 Live2D 模型互动时触发主动对话决策。
 * 后端会先判断旧话题状态：未结束则续聊，已结束才联网寻找新话题。
 */
const triggerProactiveFromInteract = async () => {
  if (!currentConversationId.value || !user.value) {
    live2dController.setMotionSoundEnabled(false)
    return
  }
  if (!settings.value.proactiveChatEnabled) {
    live2dController.setMotionSoundEnabled(false)
    return
  }
  try {
    const response = await apiFetch('/api/chat/proactive/trigger', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId: currentConversationId.value })
    })
    const result = response.ok ? await response.json() : { triggered: false }
    if (!result.triggered) {
      console.warn('Live2D 主动对话被跳过', result.status || response.status)
      live2dController.setMotionSoundEnabled(false)
    }
  } catch (e) {
    console.warn('触发互动主动说话失败', e)
    live2dController.setMotionSoundEnabled(false)
  }
}

/**
 * 注册主动搭话：调后端注册 + 开启 SSE 长连接监听
 */
const registerProactiveChat = async () => {
  if (!settings.value.proactiveChatEnabled) return
  if (!currentConversationId.value || !user.value) return

  const roleId = selectedRole.value ? selectedRole.value.id : 1

  // 1. 调后端注册
  try {
    await apiFetch('/api/chat/proactive', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        conversationId: currentConversationId.value,
        userId: String(user.value.id),
        roleId: roleId,
        idleSeconds: settings.value.proactiveIdleSeconds,
        proactivePrompt: settings.value.proactivePrompt
      })
    })
  } catch (e) {
    console.warn('注册主动搭话失败', e)
    return
  }

  // 2. 关闭旧的 SSE 长连接
  closeProactiveEventSource()

  // 3. 开启新的 SSE 长连接
  const url = apiUrl(`/api/chat/proactive/stream?conversationId=${encodeURIComponent(currentConversationId.value)}`)
  proactiveEventSource = new EventSource(url)

  proactiveEventSource.addEventListener('proactive_status', (e) => {
    let payload = {}
    try { payload = JSON.parse(e.data) } catch {}
    if (payload.phase === 'no_reliable_topic' || payload.phase === 'search_unavailable') {
      if (payload.trigger === 'live2d') {
        messages.value.push({
          role: 'ai',
          content: payload.phase === 'search_unavailable'
            ? '我刚想联网找点新话题，但现在检索服务暂时不可用。'
            : '我刚刚联网找了找，不过暂时没有找到足够可靠、适合聊的新内容。',
          isProactive: true,
          proactiveMode: 'research_unavailable',
          proactiveTrigger: 'live2d'
        })
        scrollToBottom()
      }
      live2dController.setMotionSoundEnabled(false)
    }
  })

  proactiveEventSource.addEventListener('proactive', (e) => {
    proactiveTtsPlaybackSession = createTtsPlaybackSession()
    // 收到主动搭话标记 → 在消息列表中新增 AI 消息
    let payload = {}
    try { payload = JSON.parse(e.data) } catch {}
    const aiMsgIndex = messages.value.length
    messages.value.push({
      role: 'ai', content: '', isProactive: true,
      candidateId: payload.candidateId || null,
      sources: Array.isArray(payload.sources) ? payload.sources : [],
      proactiveReason: payload.reason || '',
      proactiveMode: payload.mode || 'continuation',
      proactiveTrigger: payload.trigger || 'timer'
    })
    scrollToBottom()
    // 把当前 aiMsgIndex 关联到 proactive 会话
    proactiveAiMsgIndex = aiMsgIndex
  })

  proactiveEventSource.addEventListener('text', (e) => {
    // 主动搭话的文本 delta
    let payload = {}
    try { payload = JSON.parse(e.data) } catch {}
    if (proactiveAiMsgIndex !== null && payload.delta) {
      messages.value[proactiveAiMsgIndex].content += payload.delta
      scrollToBottom()
    }
  })

  proactiveEventSource.addEventListener('emotion', (e) => {
    let payload = {}
    try { payload = JSON.parse(e.data) } catch {}
    live2dController.triggerEmotion(payload.emotion)
  })

  proactiveEventSource.addEventListener('tts', (e) => {
    // 主动搭话的 TTS 事件（如果后端支持）
    let payload = {}
    try { payload = JSON.parse(e.data) } catch {}
    // EventSource 断线重连时可能漏掉 proactive 标记，仍为这轮回复补一个独立会话。
    if (!proactiveTtsPlaybackSession) {
      proactiveTtsPlaybackSession = createTtsPlaybackSession()
    }
    handleTtsEvent(payload, proactiveTtsPlaybackSession)
  })

  proactiveEventSource.addEventListener('done', (e) => {
    // 主动搭话完成
    proactiveAiMsgIndex = null
    live2dController.onConversationEnd()
  })

  proactiveEventSource.addEventListener('interrupted', (e) => {
    // 主动搭话被打断
    if (proactiveAiMsgIndex !== null && !messages.value[proactiveAiMsgIndex]?.content) {
      messages.value[proactiveAiMsgIndex].content = '[已打断]'
    }
    proactiveAiMsgIndex = null
    // 用户输入导致主动搭话中断时，禁止当前主动回复晚到的 TTS chunk 再次播放。
    if (isTtsPlaybackSessionActive(proactiveTtsPlaybackSession)) {
      stopCurrentAudio()
    }
    proactiveTtsPlaybackSession = null
    live2dController.setMotionSoundEnabled(false)
  })

  proactiveEventSource.addEventListener('error', (e) => {
    let payload = {}
    try { payload = JSON.parse(e.data) } catch {}
    console.warn('主动搭话错误', payload.message)
    proactiveAiMsgIndex = null
    live2dController.setMotionSoundEnabled(false)
  })

  proactiveEventSource.onerror = () => {
    // SSE 连接断开，尝试重连（EventSource 会自动重连）
    console.log('主动搭话 SSE 连接断开，将自动重连')
  }
}

let proactiveAiMsgIndex = null  // 当前主动搭话的 AI 消息 index

const sendProactiveFeedback = async (msg, feedback) => {
  if (!msg?.candidateId || !user.value) return
  try {
    const resp = await apiFetch(`/api/proactive-research/candidates/${msg.candidateId}/feedback`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: user.value.id, feedback })
    })
    const data = await resp.json()
    if (data.ok) msg.feedback = feedback
  } catch (e) {
    ElMessage.error('反馈保存失败')
  }
}

/**
 * 注销主动搭话：调后端注销 + 关闭 SSE 长连接
 */
const unregisterProactiveChat = async () => {
  if (!currentConversationId.value) return
  closeProactiveEventSource()
  try {
    await apiFetch('/api/chat/proactive', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId: currentConversationId.value })
    })
  } catch (e) {
    console.warn('注销主动搭话失败', e)
  }
}

const closeProactiveEventSource = () => {
  if (proactiveEventSource) {
    proactiveEventSource.close()
    proactiveEventSource = null
  }
  proactiveAiMsgIndex = null
  proactiveTtsPlaybackSession = null
}
</script>

<style>
.dot { transition: all 0.3s ease-in-out; }
input:checked ~ .dot { transform: translateX(100%); background-color: #fff; }
</style>
