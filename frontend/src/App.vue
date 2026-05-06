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
          <label class="block text-xs font-semibold text-gray-600 mb-1">扮演角色</label>
          <select v-model="selectedRole" @change="onRoleChange" class="w-full px-2 py-1.5 text-sm bg-white border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500">
            <option v-for="role in roles" :key="role.id" :value="role">{{ role.name }}</option>
          </select>
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
      <div class="p-4 border-t flex items-center justify-between">
        <div class="flex items-center gap-2 overflow-hidden">
          <div class="w-8 h-8 bg-blue-500 text-white rounded-full flex items-center justify-center font-bold flex-shrink-0">
            {{ user?.username?.charAt(0).toUpperCase() }}
          </div>
          <div class="text-sm font-medium truncate">{{ user?.username }}</div>
        </div>
        <button @click="logout" class="text-xs text-gray-500 hover:text-red-500 transition ml-2">退出</button>
      </div>
    </div>

    <!-- Main Chat Area -->
    <div class="flex-1 flex flex-col relative">
      <!-- Header -->
      <div class="h-14 border-b flex items-center px-4 justify-between bg-white md:hidden">
        <div class="font-semibold text-sm truncate flex-1">
          AI Chat 
          <select v-if="selectedRole" v-model="selectedRole" @change="onRoleChange" class="ml-2 text-xs bg-transparent border-none text-gray-500 w-24">
            <option v-for="role in roles" :key="role.id" :value="role">{{ role.name }}</option>
          </select>
        </div>
        <div v-if="user" @click="logout" class="text-xs text-gray-500 cursor-pointer shrink-0 ml-4">退出</div>
      </div>

      <!-- Messages List -->
      <div class="flex-1 overflow-y-auto p-4 md:p-8 space-y-6 no-scrollbar pb-40" id="chat-container">
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
                <path fill-rule="evenodd" d="M9.383 3.076A1 1 0 0110 4v12a1 1 0 01-1.707.707L4.586 13H2a1 1 0 01-1-1V8a1 1 0 011-1h2.586l3.707-3.707a1 1 0 011.09-.217zM14.657 2.929a1 1 0 011.414 0A9.972 9.972 0 0119 10a9.972 9.972 0 01-2.929 7.071 1 1 0 01-1.414-1.414A7.971 7.971 0 0017 10c0-2.21-.894-4.208-2.343-5.657a1 1 0 010-1.414zm-2.829 2.828a1 1 0 011.415 0A5.983 5.983 0 0115 10a5.984 5.984 0 01-1.757 4.243 1 1 0 01-1.415-1.415A3.984 3.984 0 0013 10a3.983 3.983 0 00-1.172-2.828 1 1 0 010-1.415z" clip-rule="evenodd" />
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
      <div class="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-white via-white to-transparent pt-6 pb-6 px-4 md:px-8">
        <div class="max-w-3xl mx-auto flex flex-col gap-2">
          
          <div class="flex items-center justify-between px-2">
            <button @click="toggleVoiceMode" class="text-xs px-3 py-1 rounded-full transition" :class="isVoiceMode ? 'bg-pink-500 text-white' : 'bg-gray-200 text-gray-600'">
              {{ isVoiceMode ? '🎙️ 按住说话模式' : '⌨️ 键盘输入模式' }}
            </button>
            <div class="flex items-center gap-4">
              <!-- Toggle RAG -->
              <label class="flex items-center cursor-pointer group">
                <div class="relative">
                  <input type="checkbox" v-model="useRag" class="sr-only">
                  <div class="block bg-gray-200 group-hover:bg-gray-300 w-8 h-4 rounded-full transition-colors" :class="{'bg-blue-400 group-hover:bg-blue-500': useRag}"></div>
                  <div class="dot absolute left-0.5 top-0.5 bg-white w-3 h-3 rounded-full transition transform" :class="{'translate-x-4': useRag}"></div>
                </div>
                <div class="ml-2 text-xs font-medium text-gray-500 transition-colors" :class="{'text-blue-600': useRag}">RAG</div>
              </label>
              <!-- Toggle Web Search -->
              <label class="flex items-center cursor-pointer group">
                <div class="relative">
                  <input type="checkbox" v-model="useSearch" class="sr-only">
                  <div class="block bg-gray-200 group-hover:bg-gray-300 w-8 h-4 rounded-full transition-colors" :class="{'bg-emerald-400 group-hover:bg-emerald-500': useSearch}"></div>
                  <div class="dot absolute left-0.5 top-0.5 bg-white w-3 h-3 rounded-full transition transform" :class="{'translate-x-4': useSearch}"></div>
                </div>
                <div class="ml-2 text-xs font-medium text-gray-500 transition-colors" :class="{'text-emerald-600': useSearch}">Web Search</div>
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
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const inputRaw = ref('')
const messages = ref([])
const loading = ref(false)

const useRag = ref(true)
const useSearch = ref(false)

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
  conversations.value = []
  messages.value = []
  loginForm.value = { username: '', password: '' }
}

const loadRoles = async () => {
  try {
    const res = await fetch('/api/roles')
    const data = await res.json()
    roles.value = data || []
    if (roles.value.length > 0 && !selectedRole.value) {
      selectedRole.value = roles.value[0]
    }
  } catch(e) {
    console.error('Failed to load roles', e)
  }
}

const onRoleChange = () => {
  ElMessage.success(`切换至体验角色`)
}

const loadConversations = async () => {
  if (!user.value) return
  try {
    const res = await fetch(`/api/conversation/user/` + user.value.id)
    const data = await res.json()
    conversations.value = data || []
  } catch (e) {
    console.error('Failed to load conversations', e)
  }
}

const initDataAfterLogin = async () => {
  await loadRoles()
  await loadConversations()
  newChat()
}

const selectConversation = async (id) => {
  if (currentConversationId.value === id) return
  currentConversationId.value = id
  messages.value = []
  try {
    const res = await fetch(`/api/conversation/` + id + `/history`)
    const history = await res.json()
    if (history && history.length > 0) {
      messages.value = history.map(h => ({
        role: h.sender === 'user' ? 'user' : 'ai',
        content: h.content
      }))
      scrollToBottom()
    }
  } catch (e) {
    console.error('Failed to load history', e)
  }
}

const deleteConversation = async (id) => {
  try {
    await ElMessageBox.confirm('确定删除吗？', '提示', { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' })
  } catch (cancel) { return }
  try {
    const res = await fetch(`/api/conversation/` + id, { method: 'DELETE' })
    if (res.ok) {
      if (currentConversationId.value === id) newChat()
      await loadConversations()
      ElMessage.success('删除成功')
    } else {
      ElMessage.error('删除失败')
    }
  } catch (e) { ElMessage.error('删除失败') }
}

const newChat = () => {
  currentConversationId.value = 'conv_' + Date.now()
  messages.value = []
}

onMounted(() => {
  const saved = localStorage.getItem('chat_user')
  if (saved) {
    user.value = JSON.parse(saved)
    initDataAfterLogin()
  }
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
    rag: useRag.value,
    roleId: selectedRole.value ? selectedRole.value.id : 1
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
        let finalText = eventData.join('\n')
        if (finalText && finalText !== '[DONE]') {
          messages.value[aiMsgIndex].content += finalText
          scrollToBottom()
        }
      }
    }
    if (messages.value.length <= 2) await loadConversations()
  } catch (error) {
    if (!messages.value[aiMsgIndex].content) messages.value[aiMsgIndex].content = '[Error]'
  } finally {
    loading.value = false
  }
}

const toggleVoiceMode = () => { isVoiceMode.value = !isVoiceMode.value }

const startRecording = async () => {
  if (loading.value || isRecording.value) return;
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    mediaRecorder = new MediaRecorder(stream);
    audioChunks = [];
    mediaRecorder.addEventListener("dataavailable", event => {
      audioChunks.push(event.data);
    });
    mediaRecorder.start();
    isRecording.value = true;
  } catch (err) {
    ElMessage.error("获取麦克风失败");
  }
}

const stopRecording = () => {
  if (!isRecording.value || !mediaRecorder) return;
  mediaRecorder.addEventListener("stop", async () => {
    isRecording.value = false;
    const audioBlob = new Blob(audioChunks, { type: 'audio/webm' }); 
    if (audioBlob.size < 100) return;
    await sendAudio(audioBlob);
  });
  mediaRecorder.stop();
  mediaRecorder.stream.getTracks().forEach(track => track.stop());
}

const sendAudio = async (audioBlob) => {
  loading.value = true;
  const uiAudioMsgIndex = messages.value.length;
  messages.value.push({ role: 'user', content: '...', isAudio: true });
  scrollToBottom();
  
  const formData = new FormData();
  formData.append('file', audioBlob, 'record.webm');
  formData.append('conversationId', currentConversationId.value);
  formData.append('roleId', selectedRole.value ? selectedRole.value.id : 1);
  formData.append('voiceId', selectedRole.value?.voiceId || 'default');
  formData.append('userId', user.value.id);

  try {
    const aiMsgIndex = messages.value.length;
    messages.value.push({ role: 'ai', content: '正在思考...' });

    const response = await fetch('/api/audio/chat', { method: 'POST', body: formData });
    if (!response.ok) throw new Error("HTTP error");
    
    const arrayBuffer = await response.arrayBuffer();
    const textHeader = response.headers.get('X-AI-Response-Text');
    let decodedText = '【语音回复】';
    if (textHeader) {
      decodedText = decodeURIComponent(textHeader).replace(/\+/g, ' ');
    }
    messages.value[aiMsgIndex].content = decodedText;

    const audioUrl = URL.createObjectURL(new Blob([arrayBuffer]));
    messages.value[aiMsgIndex].audioUrl = audioUrl;
    playAudio(audioUrl);
    scrollToBottom();
    
    if (messages.value.length <= 2) await loadConversations()
  } catch (error) {
    messages.value.push({ role: 'ai', content: '[Error: 无法获取语音或解析出错]' });
  } finally {
    loading.value = false;
  }
}

const playAudio = (url) => {
  const audio = new Audio(url);
  audio.play().catch(e => console.error("播音失败", e));
}
</script>
<style>
.dot { transition: all 0.3s ease-in-out; }
input:checked ~ .dot { transform: translateX(100%); background-color: #fff; }
</style>