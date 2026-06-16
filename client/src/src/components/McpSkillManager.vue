<template>
  <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
    <div class="bg-white dark:bg-gray-800 rounded-xl shadow-lg w-full max-w-3xl mx-4 flex flex-col max-h-[88vh]">
      <!-- header -->
      <div class="flex items-center justify-between p-4 border-b dark:border-gray-700">
        <h3 class="text-lg font-semibold dark:text-gray-100">扩展管理</h3>
        <button @click="$emit('close')" class="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <!-- tabs -->
      <div class="flex border-b dark:border-gray-700 px-4">
        <button
          v-for="t in tabs" :key="t.key"
          @click="activeTab = t.key"
          class="px-4 py-2 text-sm font-medium -mb-px border-b-2 transition"
          :class="activeTab === t.key
            ? 'border-blue-500 text-blue-600 dark:text-blue-400'
            : 'border-transparent text-gray-500 dark:text-gray-400 hover:text-gray-700'">
          {{ t.label }}
        </button>
        <div class="flex-1"></div>
        <button @click="reloadAll" class="px-3 py-2 text-xs text-gray-500 hover:text-blue-500" title="热重载所有 MCP 服务器">
          🔄 重载
        </button>
      </div>

      <div class="p-4 overflow-y-auto flex-1">
        <!-- ===================== MCP 服务器 ===================== -->
        <div v-show="activeTab === 'mcp'">
          <div class="flex justify-between items-center mb-3">
            <p class="text-xs text-gray-400">联网搜索由内置的 SearXNG MCP 提供（webSearch 工具）。可在此添加更多本地/远程 MCP 服务器。</p>
            <button @click="newServer" class="text-xs bg-blue-500 text-white rounded-md px-3 py-1.5 hover:bg-blue-600">+ 添加服务器</button>
          </div>

          <div v-if="servers.length === 0" class="text-center text-gray-400 text-sm py-8">暂无 MCP 服务器</div>

          <div v-for="item in servers" :key="item.config.id"
               class="border dark:border-gray-700 rounded-lg p-3 mb-3">
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <div class="flex items-center gap-2 flex-wrap">
                  <span class="font-medium dark:text-gray-100">{{ item.config.name }}</span>
                  <span class="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 dark:bg-gray-700 text-gray-500">{{ item.config.transport }}</span>
                  <span v-if="item.config.builtin" class="text-[10px] px-1.5 py-0.5 rounded bg-amber-100 text-amber-700">内置</span>
                  <span class="text-[10px] px-1.5 py-0.5 rounded"
                        :class="statusClass(item.status)">{{ statusLabel(item.status, item.config.enabled) }}</span>
                </div>
                <div class="text-xs text-gray-400 mt-1 truncate">{{ item.config.description }}</div>
                <div v-if="item.status && item.status.toolNames && item.status.toolNames.length"
                     class="text-[11px] text-gray-500 mt-1">工具：{{ item.status.toolNames.join(', ') }}</div>
                <div v-if="item.status && item.status.error" class="text-[11px] text-red-500 mt-1">错误：{{ item.status.error }}</div>
              </div>
              <div class="flex items-center gap-2 flex-shrink-0">
                <button @click="toggleServer(item)"
                        class="relative w-9 h-5 rounded-full transition-colors"
                        :class="item.config.enabled ? 'bg-emerald-500' : 'bg-gray-300'">
                  <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                        :class="item.config.enabled ? 'left-[18px]' : 'left-0.5'"></span>
                </button>
                <button @click="editServer(item.config)" class="text-xs text-gray-500 hover:text-blue-500">编辑</button>
                <button v-if="!item.config.builtin" @click="deleteServer(item.config)" class="text-xs text-gray-500 hover:text-red-500">删除</button>
              </div>
            </div>
          </div>
        </div>

        <!-- ===================== 技能 ===================== -->
        <div v-show="activeTab === 'skill'">
          <div class="flex justify-between items-center mb-3">
            <p class="text-xs text-gray-400">技能 = 一段可注入的能力说明 + 绑定的 MCP 工具。启用后会写入系统提示，引导模型在合适时机使用。</p>
            <button @click="newSkill" class="text-xs bg-blue-500 text-white rounded-md px-3 py-1.5 hover:bg-blue-600">+ 添加技能</button>
          </div>

          <div v-if="skills.length === 0" class="text-center text-gray-400 text-sm py-8">暂无技能</div>

          <div v-for="s in skills" :key="s.name" class="border dark:border-gray-700 rounded-lg p-3 mb-3">
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <div class="flex items-center gap-2">
                  <span class="font-medium dark:text-gray-100">{{ s.name }}</span>
                  <span v-if="s.mcpTools && s.mcpTools.length" class="text-[10px] px-1.5 py-0.5 rounded bg-indigo-100 text-indigo-700">{{ s.mcpTools.join(', ') }}</span>
                </div>
                <div class="text-xs text-gray-400 mt-1">{{ s.description }}</div>
              </div>
              <div class="flex items-center gap-2 flex-shrink-0">
                <button @click="toggleSkill(s)"
                        class="relative w-9 h-5 rounded-full transition-colors"
                        :class="s.enabled ? 'bg-emerald-500' : 'bg-gray-300'">
                  <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                        :class="s.enabled ? 'left-[18px]' : 'left-0.5'"></span>
                </button>
                <button @click="editSkill(s)" class="text-xs text-gray-500 hover:text-blue-500">编辑</button>
                <button @click="deleteSkill(s)" class="text-xs text-gray-500 hover:text-red-500">删除</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ============== MCP 服务器编辑弹层 ============== -->
    <div v-if="serverForm" class="fixed inset-0 z-[60] flex items-center justify-center bg-black bg-opacity-50">
      <div class="bg-white dark:bg-gray-800 rounded-xl shadow-lg w-full max-w-lg mx-4 flex flex-col max-h-[88vh]">
        <div class="flex items-center justify-between p-4 border-b dark:border-gray-700">
          <h3 class="font-semibold dark:text-gray-100">{{ serverForm.id ? '编辑' : '添加' }} MCP 服务器</h3>
          <button @click="serverForm = null" class="text-gray-400 hover:text-gray-600">✕</button>
        </div>
        <div class="p-4 space-y-3 overflow-y-auto">
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">名称</label>
            <input v-model="serverForm.name" type="text" class="form-input" placeholder="例如 文件系统 MCP" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">说明</label>
            <input v-model="serverForm.description" type="text" class="form-input" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">传输方式</label>
            <select v-model="serverForm.transport" class="form-input">
              <option value="stdio">stdio（本地子进程）</option>
              <option value="sse">sse（远程 HTTP）</option>
            </select>
          </div>

          <template v-if="serverForm.transport === 'stdio'">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">启动命令（每行一个参数）</label>
              <textarea v-model="serverForm.commandText" rows="5" class="form-input font-mono text-xs"
                placeholder="npx&#10;-y&#10;@modelcontextprotocol/server-filesystem&#10;/some/path"></textarea>
              <p class="text-[11px] text-gray-400 mt-1">相对的 *.jar 路径会按项目根解析。</p>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">环境变量（每行 KEY=VALUE）</label>
              <textarea v-model="serverForm.envText" rows="2" class="form-input font-mono text-xs" placeholder="SEARXNG_URL=http://localhost:8888"></textarea>
            </div>
          </template>

          <template v-else>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">SSE URL</label>
              <input v-model="serverForm.url" type="text" class="form-input" placeholder="https://host/mcp/sse" />
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">请求头（每行 KEY=VALUE）</label>
              <textarea v-model="serverForm.headersText" rows="2" class="form-input font-mono text-xs" placeholder="Authorization=Bearer xxx"></textarea>
            </div>
          </template>

          <label class="flex items-center gap-2 text-sm dark:text-gray-300">
            <input type="checkbox" v-model="serverForm.enabled" /> 启用
          </label>
        </div>
        <div class="flex justify-between gap-2 p-4 border-t dark:border-gray-700">
          <button @click="testServer" :disabled="busy" class="text-sm border border-gray-300 dark:border-gray-600 rounded-md px-4 py-2 hover:bg-gray-50 dark:hover:bg-gray-700">测试连接</button>
          <div class="flex gap-2">
            <button @click="serverForm = null" class="text-sm border border-gray-300 dark:border-gray-600 rounded-md px-4 py-2">取消</button>
            <button @click="saveServer" :disabled="busy" class="text-sm bg-blue-500 text-white rounded-md px-4 py-2 hover:bg-blue-600">保存</button>
          </div>
        </div>
      </div>
    </div>

    <!-- ============== 技能编辑弹层 ============== -->
    <div v-if="skillForm" class="fixed inset-0 z-[60] flex items-center justify-center bg-black bg-opacity-50">
      <div class="bg-white dark:bg-gray-800 rounded-xl shadow-lg w-full max-w-lg mx-4 flex flex-col max-h-[88vh]">
        <div class="flex items-center justify-between p-4 border-b dark:border-gray-700">
          <h3 class="font-semibold dark:text-gray-100">{{ skillForm.dirName ? '编辑' : '添加' }} 技能</h3>
          <button @click="skillForm = null" class="text-gray-400 hover:text-gray-600">✕</button>
        </div>
        <div class="p-4 space-y-3 overflow-y-auto">
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">名称</label>
            <input v-model="skillForm.name" type="text" class="form-input" :disabled="!!skillForm.dirName" placeholder="web-research" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">说明（模型据此判断何时使用）</label>
            <input v-model="skillForm.description" type="text" class="form-input" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">绑定 MCP 工具（逗号分隔）</label>
            <input v-model="skillForm.mcpToolsText" type="text" class="form-input" placeholder="webSearch" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">指令正文（Markdown）</label>
            <textarea v-model="skillForm.instructions" rows="8" class="form-input text-xs"></textarea>
          </div>
          <label class="flex items-center gap-2 text-sm dark:text-gray-300">
            <input type="checkbox" v-model="skillForm.enabled" /> 启用
          </label>
        </div>
        <div class="flex justify-end gap-2 p-4 border-t dark:border-gray-700">
          <button @click="skillForm = null" class="text-sm border border-gray-300 dark:border-gray-600 rounded-md px-4 py-2">取消</button>
          <button @click="saveSkill" :disabled="busy" class="text-sm bg-blue-500 text-white rounded-md px-4 py-2 hover:bg-blue-600">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { apiFetch } from '../utils/api.js'

const props = defineProps({ show: { type: Boolean, default: false } })
defineEmits(['close'])

const tabs = [
  { key: 'mcp', label: 'MCP 服务器' },
  { key: 'skill', label: '技能' },
]
const activeTab = ref('mcp')
const servers = ref([])
const skills = ref([])
const serverForm = ref(null)
const skillForm = ref(null)
const busy = ref(false)

watch(() => props.show, (v) => { if (v) { loadServers(); loadSkills() } })

// ---------------- MCP ----------------
async function loadServers() {
  try {
    const res = await apiFetch('/api/mcp/servers')
    servers.value = await res.json()
  } catch (e) { ElMessage.error('加载 MCP 服务器失败') }
}

function statusLabel(status, enabled) {
  if (!enabled) return '已停用'
  if (!status) return '未知'
  if (status.connected) return `已连接 · ${status.toolCount} 工具`
  return '连接失败'
}
function statusClass(status) {
  if (status && status.connected) return 'bg-emerald-100 text-emerald-700'
  if (status && status.error) return 'bg-red-100 text-red-700'
  return 'bg-gray-100 text-gray-500'
}

function newServer() {
  serverForm.value = { id: '', name: '', description: '', enabled: true, transport: 'stdio',
    commandText: '', envText: '', url: '', headersText: '' }
}
function editServer(cfg) {
  serverForm.value = {
    id: cfg.id, name: cfg.name, description: cfg.description, enabled: cfg.enabled,
    transport: cfg.transport, builtin: cfg.builtin,
    commandText: (cfg.command || []).join('\n'),
    envText: mapToText(cfg.env),
    url: cfg.url || '',
    headersText: mapToText(cfg.headers),
  }
}

function buildServerPayload() {
  const f = serverForm.value
  const payload = {
    id: f.id || undefined, name: f.name, description: f.description,
    enabled: f.enabled, transport: f.transport,
  }
  if (f.transport === 'stdio') {
    payload.command = f.commandText.split('\n').map(s => s.trim()).filter(Boolean)
    payload.env = textToMap(f.envText)
  } else {
    payload.url = f.url
    payload.headers = textToMap(f.headersText)
  }
  return payload
}

async function saveServer() {
  if (!serverForm.value.name) { ElMessage.warning('请填写名称'); return }
  busy.value = true
  try {
    const res = await apiFetch('/api/mcp/servers', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(buildServerPayload()),
    })
    if (!res.ok) throw new Error()
    ElMessage.success('已保存并重载')
    serverForm.value = null
    await loadServers()
  } catch (e) { ElMessage.error('保存失败') } finally { busy.value = false }
}

async function testServer() {
  busy.value = true
  try {
    const res = await apiFetch('/api/mcp/servers/test', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(buildServerPayload()),
    })
    const data = await res.json()
    if (data.ok) ElMessage.success(`连接成功，${data.toolCount} 个工具：${(data.tools || []).join(', ')}`)
    else ElMessage.error('连接失败：' + (data.error || '未知错误'))
  } catch (e) { ElMessage.error('测试失败') } finally { busy.value = false }
}

async function toggleServer(item) {
  const payload = { ...item.config, enabled: !item.config.enabled }
  try {
    await apiFetch('/api/mcp/servers', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    await loadServers()
  } catch (e) { ElMessage.error('操作失败') }
}

async function deleteServer(cfg) {
  try {
    await ElMessageBox.confirm(`确定删除 MCP 服务器「${cfg.name}」？`, '确认', { type: 'warning' })
  } catch { return }
  try {
    const res = await apiFetch(`/api/mcp/servers/${cfg.id}`, { method: 'DELETE' })
    if (!res.ok) { const d = await res.json(); throw new Error(d.message) }
    ElMessage.success('已删除')
    await loadServers()
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

async function reloadAll() {
  try {
    await apiFetch('/api/mcp/reload', { method: 'POST' })
    ElMessage.success('已重载')
    await loadServers()
  } catch (e) { ElMessage.error('重载失败') }
}

// ---------------- Skills ----------------
async function loadSkills() {
  try {
    const res = await apiFetch('/api/skills')
    skills.value = await res.json()
  } catch (e) { ElMessage.error('加载技能失败') }
}

function newSkill() {
  skillForm.value = { name: '', description: '', enabled: true, mcpToolsText: '', instructions: '', dirName: '' }
}
function editSkill(s) {
  skillForm.value = {
    name: s.name, description: s.description, enabled: s.enabled,
    mcpToolsText: (s.mcpTools || []).join(', '), instructions: s.instructions, dirName: s.dirName,
  }
}

async function saveSkill() {
  if (!skillForm.value.name) { ElMessage.warning('请填写名称'); return }
  busy.value = true
  const f = skillForm.value
  const payload = {
    name: f.name, description: f.description, enabled: f.enabled,
    mcpTools: f.mcpToolsText.split(',').map(s => s.trim()).filter(Boolean),
    instructions: f.instructions, dirName: f.dirName || undefined,
  }
  try {
    const res = await apiFetch('/api/skills', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    if (!res.ok) throw new Error()
    ElMessage.success('已保存')
    skillForm.value = null
    await loadSkills()
  } catch (e) { ElMessage.error('保存失败') } finally { busy.value = false }
}

async function toggleSkill(s) {
  try {
    await apiFetch(`/api/skills/${encodeURIComponent(s.name)}/toggle?enabled=${!s.enabled}`, { method: 'PUT' })
    await loadSkills()
  } catch (e) { ElMessage.error('操作失败') }
}

async function deleteSkill(s) {
  try {
    await ElMessageBox.confirm(`确定删除技能「${s.name}」？`, '确认', { type: 'warning' })
  } catch { return }
  try {
    await apiFetch(`/api/skills/${encodeURIComponent(s.name)}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await loadSkills()
  } catch (e) { ElMessage.error('删除失败') }
}

// ---------------- helpers ----------------
function mapToText(map) {
  if (!map) return ''
  return Object.entries(map).map(([k, v]) => `${k}=${v}`).join('\n')
}
function textToMap(text) {
  const map = {}
  ;(text || '').split('\n').map(s => s.trim()).filter(Boolean).forEach(line => {
    const i = line.indexOf('=')
    if (i > 0) map[line.slice(0, i).trim()] = line.slice(i + 1).trim()
  })
  return map
}
</script>

<style scoped>
.form-input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  background: transparent;
}
.dark .form-input { border-color: #4b5563; color: #f3f4f6; }
.form-input:focus { outline: none; box-shadow: 0 0 0 1px #3b82f6; border-color: #3b82f6; }
</style>
