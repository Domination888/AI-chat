<template>
  <div class="flex h-screen overflow-hidden bg-white text-gray-800">
    <!-- Login Overlay -->
    <div v-if="!user" class="absolute inset-0 z-50 flex items-center justify-center bg-gray-50">
      <div class="bg-white p-8 rounded-xl shadow-md w-full max-w-sm border border-gray-100">
        <h2 class="text-2xl font-bold mb-6 text-center text-gray-800">登录 AI Chat</h2>
        <div class="space-y-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">用户名</label>
            <input v-model="loginForm.username" type="text" class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500" placeholder="任意用户名(自动注册)" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">密码</label>
            <input v-model="loginForm.password" type="password" class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500" @keydown.enter="doLogin" placeholder="请输入密码" />
          </div>
          <p v-if="loginError" class="text-red-500 text-sm">{{ loginError }}</p>
          <button @click="doLogin" :disabled="isLoggingIn" class="w-full py-2 px-4 bg-blue-600 text-white rounded-md hover:bg-blue-700 transition disabled:opacity-50 mt-4">
            {{ isLoggingIn ? '登录中...' : '登 录' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Sidebar -->
    <div class="w-64 bg-gray-50 border-r flex flex-col hidden md:flex">
      <div class="p-4 border-b space-y-3">
        <!-- Role Selector -->
        <div>
          <button 
            @click="showRoleSelector = true"
            class="w-full flex items-center gap-2 px-3 py-2 bg-white border border-gray-300 rounded-md hover:bg-gray-50 transition text-sm"
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
        <button @click="newChat" class="w-full flex items-center justify-center gap-2 bg-white border border-gray-300 rounded-md py-2 px-4 hover:bg-gray-50 transition">
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
             :class="currentConversationId === conv.id ? 'bg-blue-100 text-blue-700 font-medium' : 'hover:bg-gray-200 text-gray-700'">
            <span class="truncate block flex-1">{{ conv.title || '新对话' }}</span>
            <button @click.stop="deleteConversation(conv.id)" title="删除对话" class="text-gray-400 hover:text-red-500 opacity-0 group-hover:opacity-100 transition-opacity ml-2 shrink-0">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
              </svg>
            </button>
        </div>
      </div>
      <!-- User profile block at bottom of sidebar -->
      <div class="p-4 border-t">
        <div class="flex items-center gap-2 overflow-hidden mb-2">
          <div class="w-8 h-8 bg-blue-500 text-white rounded-full flex items-center justify-center font-bold flex-shrink-0">
            {{ user?.username?.charAt(0).toUpperCase() }}
          </div>
          <div class="text-sm font-medium truncate">{{ user?.username }}</div>
        </div>
        <div class="flex gap-2">
          <button @click="openSettings" class="flex-1 text-xs border border-gray-300 rounded-md py-1.5 hover:bg-gray-50 transition">
            ⚙️ 设置
          </button>
          <button @click="logout" class="flex-1 text-xs text-gray-500 hover:text-red-500 border border-gray-300 rounded-md py-1.5 transition">
            退出
          </button>
        </div>
      </div>
    </div>

    <!-- Main Chat Area -->
    <div class="flex-1 flex flex-col relative">
      <!-- Header -->
      <div class="h-14 border-b flex items-center px-4 justify-between bg-white md:hidden">
        <button 
          @click="showRoleSelector = true"
          class="flex items-center gap-1.5 text-sm text-gray-700"
        >
          <div class="w-6 h-6 rounded-full bg-gradient-to-br from-pink-400 to-purple-500 flex items-center justify-center text-white font-bold text-xs flex-shrink-0">
            {{ selectedRole?.name?.charAt(0) || '?' }}
          </div>
          <span class="truncate max-w-[120px]">{{ selectedRole?.name || '选择角色' }}</span>
        </button>
        <div v-if="user" @click="logout" class="text-xs text-gray-500 cursor-pointer shrink-0 ml-4">退出</div>
      </div>

      <!-- Messages List -->
      <div class="flex-1 overflow-y-auto p-4 md:p-8 space-y-6 no-scrollbar pb-[280px]" id="chat-container">
        <div v-if="messages.length === 0" class="h-full flex flex-col items-center justify-center text-gray-400">
          <div class="text-4xl mb-4 text-gray-300">👋</div>
          <h2 class="text-xl font-semibold mb-2">有什么我可以帮您的？</h2>
          <p class="text-sm mb-1">现在正在使用体验角色：<span class="text-blue-500 font-bold">{{ selectedRole?.name || '未知角色' }}</span></p>
          <p class="text-xs">按住麦克风说话，使用声音沟通！</p>
        </div>
        
        <div v-for="(msg, index) in messages" :key="index" class="flex gap-4" :class="{'flex-row-reverse': msg.role === 'user'}">
          <!-- Avatar -->
          <div class="w-8 h-8 rounded-full flex items-center justify-center text-white shrink-0 text-sm"
               :class="msg.role === 'user' ? 'bg-blue-500' : 'bg-pink-500'">
            {{ msg.role === 'user' ? 'U' : (selectedRole?.name?.charAt(0) || 'AI') }}
          </div>
          <!-- Bubble -->
          <div class="max-w-[80%] rounded-2xl px-4 py-3 text-sm leading-relaxed"
               :class="msg.role === 'user' ? 'bg-blue-100 text-blue-900 rounded-tr-sm' : 'bg-gray-100 text-gray-800 rounded-tl-sm'">
            
            <div v-if="msg.images && msg.images.length > 0" class="flex flex-wrap gap-2 mb-2">
              <img v-for="(img, idx) in msg.images" :key="idx" :src="img" class="max-w-[200px] max-h-[200px] rounded-md object-cover border border-gray-200" />
            </div>
            
            <div v-if="msg.isAudio" class="flex items-center gap-2 mb-2 text-pink-600 font-bold">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 animate-pulse" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M9.383 3.076A1 1 0 0110 4v12a1 1 0 01-1.707.707L4.586 13H2a1 1 0 011-1V8a1 1 0 011-1h2.586l3.707-3.707a1 1 0 011.09-.217zM14.657 2.929a1 1 0 011.414 0A9.972 9.972 0 0119 10a9.972 9.972 0 01-2.929 7.071 1 1 0 01-1.414-1.414A7.971 7.971 0 0017 10c0-2.21-.894-4.208-2.343-5.657a1 1 0 010-1.414zm-2.829 2.828a1 1 0 011.415 0A5.983 5.983 0 0115 10a5.984 5.984 0 01-1.757 4.243 1 1 0 01-1.415-1.415A3.984 3.984 0 0013 10a3.983 3.983 0 00-1.172-2.828 1 1 0 010-1.415z" clip-rule="evenodd" />
              </svg>
              <span>[语音交互消息]</span>
            </div>

            <div style="white-space: pre-wrap; word-wrap: break-word;">{{ msg.content }}</div>
            
            <button v-if="msg.audioUrl && msg.role === 'ai'" @click="playAudio(msg.audioUrl)" class="mt-2 text-xs bg-pink-100 hover:bg-pink-200 text-pink-700 py-1 px-2 rounded flex items-center gap-1 transition">
              ▶ 播放语音
            </button>
          </div>
        </div>
      </div>

      <!-- Input Area -->
      <div class="absolute bottom-0 left-0 right-0 bg-white pt-4 pb-6 px-4 md:px-8 border-t border-gray-100">
        <div class="max-w-3xl mx-auto flex flex-col gap-2">
          
          <div class="flex items-center justify-between px-2">
            <div class="flex items-center gap-2">
              <button @click="toggleVoiceMode" class="text-xs px-3 py-1 rounded-full transition" :class="isVoiceMode ? 'bg-pink-500 text-white' : 'bg-gray-200 text-gray-600'">
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

              <!-- Web Search：联网搜索（智谱 Web Search MCP），默认关闭，由用户手动开启 -->
              <label class="flex items-center cursor-pointer select-none" title="联网搜索（智谱 Web Search MCP）">
                <span class="mr-2 text-xs font-medium transition-colors"
                      :class="useSearch ? 'text-emerald-600' : 'text-gray-500'">联网</span>
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

          <div class="relative flex flex-col border border-gray-300 bg-white rounded-xl shadow-sm overflow-hidden focus-within:ring-1 focus-within:ring-blue-500 focus-within:border-blue-500 min-h-[56px] justify-center text-center">
            
            <template v-if="isVoiceMode">
              <button 
                @mousedown.prevent="startRecording" 
                @mouseup.prevent="stopRecording" 
                @mouseleave.prevent="stopRecording"
                @touchstart.prevent="startRecording"
                @touchend.prevent="stopRecording"
                class="w-full py-4 font-bold text-gray-700 transition"
                :class="isRecording ? 'bg-pink-100 text-pink-600' : 'hover:bg-gray-50'"
                :disabled="loading"
              >
                {{ isRecording ? '🔊 松手发送...' : '按住 说话' }}
              </button>
            </template>
            
            <template v-else>
              <div v-if="selectedImages.length > 0" class="flex flex-wrap gap-2 p-3 bg-gray-50 border-b border-gray-100">
                <div v-for="(img, idx) in selectedImages" :key="idx" class="relative group">
                  <img :src="img" class="w-16 h-16 object-cover rounded-md border border-gray-200" />
                  <button @click="removeImage(idx)" class="absolute -top-2 -right-2 bg-gray-800 text-white rounded-full w-5 h-5 flex items-center justify-center text-xs opacity-0 group-hover:opacity-100 transition shadow-sm">x</button>
                </div>
              </div>
              <div class="flex items-end relative">
                <input type="file" ref="fileInput" @change="handleImageUpload" accept="image/*" multiple class="hidden" />
                <button @click="$refs.fileInput.click()" class="h-10 w-10 flex items-center justify-center text-gray-400 hover:text-blue-500 transition mb-[8px] ml-2 shrink-0">
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                  </svg>
                </button>
                <textarea
                  v-model="inputRaw"
                  @keydown.enter.prevent="sendMessage"
                  class="w-full max-h-48 min-h-[56px] py-4 pl-2 pr-12 bg-transparent border-none focus:ring-0 resize-none outline-none text-sm"
                  placeholder="发送消息... (Shift + Enter 换行)"
                  rows="1"
                ></textarea>
                <button
                  @click="sendMessage"
                  :disabled="loading || (!inputRaw.trim() && selectedImages.length === 0)"
                  class="absolute right-2 bottom-3 w-8 h-8 flex items-center justify-center rounded-lg text-white transition disabled:opacity-50"
                  :class="(inputRaw.trim() || selectedImages.length > 0) && !loading ? 'bg-blue-500 hover:bg-blue-600' : 'bg-gray-300'"
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
  
  <!-- 设置模态框 -->
  <SettingsModal 
    :show="showSettings" 
    @close="showSettings = false"
    @save="handleSettingsSave"
    ref="settingsModal"
  />
  
  <!-- 角色选择器模态框 -->
  <div v-if="showRoleSelector" class="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
    <div class="bg-white rounded-xl shadow-lg w-full max-w-4xl mx-4 max-h-[80vh] overflow-hidden">
      <RoleCardSelector 
        :roles="roles"
        :selected-role-id="selectedRole?.id || null"
        @select="(role) => { selectedRole = role; showRoleSelector = false; onRoleChange(); }"
        @close="showRoleSelector = false"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import SettingsModal from './components/SettingsModal.vue'
import RoleCardSelector from './components/RoleCardSelector.vue'

const inputRaw = ref('')
const messages = ref([])
const loading = ref(false)

// 联网搜索：默认关，由用户手动 toggle；其它能力（RAG、本地 MCP 工具）默认常开，前端不暴露开关
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

const conversations = ref([])
const currentConversationId = ref('')

const isVoiceMode = ref(false)
const isRecording = ref(false)
let mediaRecorder = null
let audioChunks = []

// 设置相关
const showSettings = ref(false)
const showRoleSelector = ref(false)
const settings = ref({
  modelBaseUrl: null,
  modelName: null,
  asrService: 'sensevoice',
  asrLanguage: 'zh',
  ttsSpeed: 1.0,
  ttsPitch: 1.0,
  autoPlayTts: true,
  darkMode: false
})

const doLogin = async () => {
  if (!loginForm.value.username || !loginForm.value.password) {
    loginError.value = '请输入用户名和密码'
    return
  }
  isLoggingIn.value = true
  loginError.value = ''
  try {
    const res = await fetch('/api/user/login', {
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
  user.value = null
  localStorage.removeItem('chat_user')
  messages.value = []
  conversations.value = []
  selectedRole.value = null
}

const loadRoles = async () => {
  try {
    const res = await fetch('/api/roles')
    const data = await res.json()
    roles.value = data || []
    if (roles.value.length > 0 && !selectedRole.value) {
      selectedRole.value = roles.value[0]
    }
  } catch (e) {
    console.error('加载角色失败', e)
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
    const res = await fetch(`/api/conversation/user/${user.value.id}/role/${selectedRole.value.id}`)
    const data = await res.json()
    conversations.value = data || []
  } catch (e) {
    console.error('加载对话列表失败', e)
  }
}

const selectConversation = async (id) => {
  currentConversationId.value = id
  try {
    const res = await fetch(`/api/conversation/${id}/history`)
    const data = await res.json()
    // 后端 History 字段映射: sender → role, content → content
    messages.value = (data || []).map(h => ({
      role: h.sender === 'assistant' ? 'ai' : h.sender,
      content: h.content || ''
    }))
  } catch (e) {
    console.error('加载对话消息失败', e)
    messages.value = []
  }
}

const newChat = async () => {
  currentConversationId.value = `conv_${Date.now()}`
  messages.value = []
}

const deleteConversation = async (id) => {
  try {
    await ElMessageBox.confirm('确定要删除这个对话吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await fetch(`/api/conversation/${id}`, { method: 'DELETE' })
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

onMounted(() => {
  const savedUser = localStorage.getItem('chat_user')
  if (savedUser) {
    try {
      user.value = JSON.parse(savedUser)
      initDataAfterLogin()
    } catch (e) {
      console.error('解析用户信息失败', e)
    }
  }
  document.addEventListener('mousedown', unlockAudioSync)
  document.addEventListener('touchstart', unlockAudioSync, true)
  document.addEventListener('mousedown', globalUnlock)
  document.addEventListener('touchstart', globalUnlock, true)
})

const scrollToBottom = async () => {
  await nextTick()
  const container = document.getElementById('chat-container')
  if (container) container.scrollTop = container.scrollHeight
}

const sendMessage = async (e) => {
  if (e && e.shiftKey) return
  const text = inputRaw.value.trim()
  const hasImages = selectedImages.value.length > 0
  if ((!text && !hasImages) || loading.value) return

  const currentImages = [...selectedImages.value]
  inputRaw.value = ''
  selectedImages.value = []
  
  messages.value.push({ role: 'user', content: text, images: currentImages })
  scrollToBottom()

  const aiMsgIndex = messages.value.length
  messages.value.push({ role: 'ai', content: '' })
  loading.value = true

  const requestBody = {
    userId: String(user.value.id),
    conversationId: currentConversationId.value,
    message: text,
    images: currentImages.length > 0 ? currentImages : null,
    stream: true,
    search: useSearch.value,
    rag: true,                       // RAG 默认常开（PLAN-001），保证角色设定 + 长期记忆准确
    tools: true,                     // 本地 MCP 工具默认常开（Gemma4 原生支持 tool-call）
    roleId: selectedRole.value ? selectedRole.value.id : 1,
    // 模型配置（如果启用了自定义模型）
    ...(settings.value.modelBaseUrl && settings.value.modelName ? {
      modelBaseUrl: settings.value.modelBaseUrl,
      modelName: settings.value.modelName
    } : {})
  }

  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestBody)
    })
    if (!response.ok) throw new Error("HTTP error!")
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let events = buffer.split(/\r?\n\r?\n/)
      buffer = events.pop() || '' 
      for (const event of events) {
        if (!event.trim()) continue
        const lines = event.split(/\r?\n/)
        let eventData = []
        for (const line of lines) {
          if (line.startsWith('data:')) {
            let text = line.substring(5)
            if (text.startsWith(' ')) text = text.substring(1)
            eventData.push(text)
          }
        }
        if (!eventData.length) continue
        messages.value[aiMsgIndex].content += eventData.join('')
        scrollToBottom()
      }
    }
    if (messages.value.length <= 3) await loadConversations()
  } catch (error) {
    console.error(error)
    if (!messages.value[aiMsgIndex].content) {
      messages.value[aiMsgIndex].content = '[Error: 请求失败]'
    }
  } finally {
    loading.value = false
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
const ttsBuffers = new Map()      // idx -> { chunks: Uint8Array[] }

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

const handleTtsEvent = (payload) => {
  const idx = payload.idx
  if (payload.chunkStart) {
    ttsBuffers.set(idx, {
      sampleRate: payload.sampleRate || 48000,
      channels: payload.channels || 1,
      format: payload.format || 's16le',
      chunks: []
    })
  }
  const buf = ttsBuffers.get(idx)
  if (!buf) return
  if (payload.audioBase64) {
    try {
      const bin = atob(payload.audioBase64)
      const bytes = new Uint8Array(bin.length)
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
      buf.chunks.push(bytes)
    } catch (e) {
      console.error('audio chunk 解码失败', e)
    }
  }
  if (payload.chunkEnd) {
    if (buf.chunks.length === 0) {
      ttsBuffers.delete(idx)
      return
    }
    let total = 0
    for (const c of buf.chunks) total += c.length
    const merged = new Uint8Array(total)
    let off = 0
    for (const c of buf.chunks) { merged.set(c, off); off += c.length }
    ttsBuffers.delete(idx)
    audioQueue.push({
      idx,
      pcm: merged,
      sampleRate: buf.sampleRate,
      channels: buf.channels,
      format: buf.format,
    })
    pumpAudioQueue()
  }
}

/**
 * 把 int16 LE 单声道 PCM 直接灌进 AudioBuffer，零解码开销。
 * 不再走 decodeAudioData（GPT-SoVITS 的 streaming wav 多 RIFF header 会让它崩）。
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
  const item = audioQueue.shift()
  if (!item) return
  audioPlaying = true
  try {
    const audioBuf = pcmInt16ToAudioBuffer(ctx, item.pcm, item.sampleRate, item.channels)
    const src = ctx.createBufferSource()
    src.buffer = audioBuf
    src.connect(ctx.destination)
    currentSource = src
    src.onended = () => {
      currentSource = null
      audioPlaying = false
      pumpAudioQueue()
    }
    src.start(0)
  } catch (e) {
    console.warn('PCM 播放失败 idx=' + item.idx + ':', e && e.message)
    audioPlaying = false
    pumpAudioQueue()
  }
}


// ============================================================
// 流式语音对话：ASR → LLM(token 流) → 句子级 TTS → 串行播放
// SSE 事件：asr / text / tts / done / error
// ============================================================
const sendAudio = async (audioBlob) => {
  loading.value = true

  // 占位：用户气泡（ASR 文本回填后替换）
  const userMsgIndex = messages.value.length
  messages.value.push({ role: 'user', content: '🎙️ 识别中...', isAudio: true })
  const aiMsgIndex = userMsgIndex + 1
  messages.value.push({ role: 'ai', content: '' })
  scrollToBottom()

  const formData = new FormData()
  formData.append('file', audioBlob, 'record.webm')
  formData.append('conversationId', currentConversationId.value)
  formData.append('roleId', selectedRole.value ? selectedRole.value.id : 1)
  formData.append('voiceId', selectedRole.value?.voiceId || '')
  formData.append('userId', user.value.id)
  formData.append('asrService', settings.value.asrService || 'sensevoice')
  formData.append('asrLanguage', settings.value.asrLanguage || 'zh')
  formData.append('ttsSpeed', settings.value.ttsSpeed.toString() || '1.0')
  formData.append('ttsPitch', settings.value.ttsPitch.toString() || '1.0')
  // 添加模型配置（如果启用了自定义模型）
  if (settings.value.modelBaseUrl && settings.value.modelName) {
    formData.append('modelBaseUrl', settings.value.modelBaseUrl)
    formData.append('modelName', settings.value.modelName)
  }

  try {
    const response = await fetch('/api/audio/chat-stream', {
      method: 'POST',
      body: formData,
      headers: { 'Accept': 'text/event-stream' }
    })
    if (!response.ok || !response.body) throw new Error('HTTP error')

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
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
        let payload = {}
        try { payload = JSON.parse(dataLines.join('\n')) } catch { payload = {} }

        if (evName === 'asr') {
          messages.value[userMsgIndex].content = payload.text || ''
          messages.value[userMsgIndex].isAudio = false
        } else if (evName === 'text') {
          messages.value[aiMsgIndex].content += (payload.delta || '')
          scrollToBottom()
        } else if (evName === 'tts') {
          handleTtsEvent(payload)
        } else if (evName === 'error') {
          messages.value[aiMsgIndex].content += `\n[错误] ${payload.message || ''}`
        } else if (evName === 'done') {
          // 流结束，啥也不做
        }
      }
    }
    if (messages.value.length <= 3) await loadConversations()
  } catch (error) {
    console.error(error)
    if (!messages.value[aiMsgIndex].content) {
      messages.value[aiMsgIndex].content = '[Error: 语音流式对话失败]'
    }
  } finally {
    loading.value = false
  }
}

const playAudio = (url) => {
  const audio = new Audio(url)
  audio.play().catch(e => console.error('播音失败', e))
}

// 设置相关方法
const openSettings = () => {
  showSettings.value = true
}

const handleSettingsSave = (newSettings) => {
  settings.value = { ...newSettings }
  // 保存到localStorage
  localStorage.setItem('appSettings', JSON.stringify(settings.value))
  ElMessage.success('设置已保存')
}

// 加载设置
const loadSettings = () => {
  const savedSettings = localStorage.getItem('appSettings')
  if (savedSettings) {
    try {
      settings.value = { ...settings.value, ...JSON.parse(savedSettings) }
    } catch (e) {
      console.error('加载设置失败', e)
    }
  }
}

// 在登录后初始化设置
const initDataAfterLogin = async () => {
  loadSettings()
  await loadRoles()
  // loadConversations 依赖 selectedRole，先确保角色已选中
  if (!selectedRole.value && roles.value.length > 0) {
    selectedRole.value = roles.value[0]
  }
  await loadConversations()
  if (conversations.value.length > 0) {
    await selectConversation(conversations.value[0].id)
  } else {
    newChat()
  }
}
</script>

<style>
.dot { transition: all 0.3s ease-in-out; }
input:checked ~ .dot { transform: translateX(100%); background-color: #fff; }
</style>