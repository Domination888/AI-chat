<template>
  <div v-if="show" class="rp-backdrop" @click.self="$emit('close')">
    <section class="rp-drawer" role="dialog" aria-modal="true" aria-label="故事与记忆">
      <header class="rp-header"><div><h2>故事与记忆</h2></div><button class="rp-close" @click="$emit('close')" aria-label="关闭故事设定">×</button></header>
      <div class="rp-tabs" aria-label="篇章设置分类">
        <button :class="{active:tab==='scene'}" @click="tab='scene'">场景设定</button>
        <button v-if="$slots.memory" :class="{active:tab==='memory'}" @click="tab='memory'">记忆与纠错</button>
        <button :class="{active:tab==='album'}" @click="tab='album'">相册与收藏</button>
      </div>
      <div class="rp-scroll no-scrollbar">
        <p v-if="error" class="rp-error" role="alert">{{ error }}</p>
        <div v-if="!state && !error" class="rp-loading">正在翻开这段故事…</div>
        <section v-if="tab==='memory'"><slot name="memory" /></section>
        <template v-if="state">
          <div v-show="tab==='scene'">
          <div class="rp-section"><h3>对话方式</h3><div class="rp-two"><label>世界时间<select v-model="state.mode" class="rp-input"><option value="story">剧情时间 · 离开后暂停</option><option value="companion">现实陪伴 · 使用当地时间</option></select></label><label>对话节奏<select v-model="state.style" class="rp-input"><option value="story">剧情演绎</option><option value="chat">面对面短聊</option></select></label></div></div>
          <div class="rp-section"><h3>当前场景</h3><div class="rp-two"><label v-for="field in fields.slice(0, 7)" :key="field[0]">{{ field[1] }}<textarea v-model="state[field[0]]" class="rp-input" rows="2" /></label></div></div>
          <div class="rp-section"><h3>人物与关系</h3><div class="rp-two"><label v-for="field in fields.slice(7, 11)" :key="field[0]">{{ field[1] }}<textarea v-model="state[field[0]]" class="rp-input" rows="2" /></label></div><label class="rp-full-label">约定 <small>每行一项：待履行 / 完成 / 取消</small><textarea v-model="commitments" class="rp-input" rows="3" /></label><label class="rp-full-label">重要经历 <small>每行一项</small><textarea v-model="events" class="rp-input" rows="3" /></label></div>
          <div class="rp-section"><h3>创作方向</h3><label class="rp-full-label">{{ fields[11][1] }}<textarea v-model="state[fields[11][0]]" class="rp-input" rows="3" /></label><label v-if="proactiveEnabled" class="rp-check"><input type="checkbox" v-model="state.paused" /> 安静陪伴，暂停主动搭话</label></div>
          </div>
          <div v-if="(state.legacyMemory || showMemoryStatus) && (tab==='memory' || !$slots.memory)" class="rp-memory" role="status"><template v-if="deliveryLoading">正在查询记忆同步状态…</template><template v-else-if="delivery.enabled===false">长期记忆同步未开启。</template><template v-else-if="delivery.pending || delivery.failed">长期记忆：待同步 {{ delivery.pending || 0 }}，失败 {{ delivery.failed || 0 }}</template><template v-else-if="delivery.enabled">当前没有待同步或失败的记忆。</template><template v-else>尚未取得记忆同步状态。</template> <button :disabled="deliveryLoading" @click="refreshDelivery">刷新同步状态</button> <button v-if="delivery.failed" :disabled="deliveryLoading" @click="retryMemory">重试失败记忆</button></div>
          <div v-show="tab==='album'" class="rp-section"><div class="rp-album-heading"><h3>共同相册与收藏</h3><button @click="loadAlbum">刷新</button></div><p v-if="!album.length" class="rp-explainer">给她看一张照片，或收藏重要的对话。</p><article v-for="item in album" :key="item.id" class="rp-album-item"><p>{{ item.user_text }}</p><p>{{ item.assistant_text }}</p><img v-for="src in images(item)" :key="src" :src="src" alt="共同看过的照片" /></article></div>
        </template>
      </div>
      <footer v-if="state && tab==='scene'" class="rp-footer"><button class="rp-continue" :disabled="busy" @click="saveAndContinue">延续世界线，开启新章</button><button class="rp-save" :disabled="busy" @click="save">{{ busy ? '保存中…' : '保存并回到故事' }} ↗</button></footer>
    </section>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { apiFetch, apiUrl } from '../utils/api.js'
const props = defineProps({ show: Boolean, conversationId: String, initialTab: {type:String,default:'scene'}, proactiveEnabled: {type:Boolean,default:true}, showMemoryStatus: {type:Boolean,default:false} }),
  emit = defineEmits(['close', 'saved', 'continue'])
const tab = ref('scene')
const state = ref(null),
  worldId = ref(''),
  commitments = ref(''),
  events = ref(''),
  album = ref([]),
  error = ref(''),
  busy = ref(false)
const delivery = ref({}), deliveryLoading = ref(false)
let deliveryRequest = 0
let panelVersion = 0
const fields = [
  ['userPersona', '故事里的我（身份与称呼）'],
  ['location', '地点'],
  ['storyTime', '故事时间'],
  ['participants', '在场人物'],
  ['outfit', '衣着'],
  ['objects', '身边的物品'],
  ['action', '正在进行的动作'],
  ['relationship', '我们的关系'],
  ['mood', '她现在的心情'],
  ['characterGoal', '她自己的计划'],
  ['sharedActivity', '一起做的事'],
  ['directorNote', '场外指导（不成为角色经历）'],
]
async function request(path, options) {
  const res = await apiFetch(path, options)
  if (!res.ok) throw new Error('场景操作失败，请稍后重试')
  const text = await res.text()
  return text ? JSON.parse(text) : null
}
async function load() {
  if (!props.conversationId) return
  const target = props.conversationId, version = panelVersion
  const current = () => props.show && target === props.conversationId && version === panelVersion
  try {
    const data = await request(`/api/rp/${target}`)
    if (!current()) return
    state.value = data.state
    worldId.value = data.worldId
    commitments.value = data.state.commitments.join('\n')
    events.value = data.state.events.join('\n')
    await loadAlbum()
    if (!current()) return
    if (state.value.legacyMemory || props.showMemoryStatus)
      await refreshDelivery()
  } catch (e) {
    if (current()) error.value = e.message
  }
}
async function refreshDelivery() {
  if (!props.conversationId) return
  deliveryLoading.value = true
  const target = props.conversationId, requestId = ++deliveryRequest
  try {
    const result = await request(`/api/rp/${target}/memory-delivery`)
    if (requestId === deliveryRequest && target === props.conversationId) delivery.value = result
  } catch (e) {
    if (requestId === deliveryRequest && target === props.conversationId) error.value = e.message
  } finally { if (requestId === deliveryRequest) deliveryLoading.value = false }
}
async function retryMemory() {
  try {
    await request(`/api/rp/${props.conversationId}/memory-retry`, { method: 'POST' })
    await refreshDelivery()
  } catch (e) {
    error.value = e.message
  }
}
async function loadAlbum() {
  const target = props.conversationId, version = panelVersion
  const current = () => props.show && target === props.conversationId && version === panelVersion
  try {
    const result = await request(`/api/rp/${target}/album`)
    if (current()) album.value = result
  } catch (e) {
    if (current()) error.value = e.message
  }
}
function images(item) {
  try {
    return JSON.parse(item.images_json).map(src=>src.startsWith('/api/')?apiUrl(src):src)
  } catch {
    return []
  }
}
async function saveScene(continueWorld) {
  if (busy.value || !props.show || !props.conversationId || !state.value) return
  const target = props.conversationId, version = panelVersion, world = worldId.value
  const current = () => props.show && target === props.conversationId && version === panelVersion
  const snapshot = { ...state.value,
    commitments: commitments.value.split('\n').filter(Boolean),
    events: events.value.split('\n').filter(Boolean),
  }
  let continued = false
  busy.value = true
  error.value = ''
  try {
    await request(`/api/rp/${target}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(snapshot),
    })
    if (!current()) return
    await request(`/api/rp/${target}/greeting`, { method: 'POST' })
    if (!current()) return
    if (continueWorld) { continued = true; emit('continue', world) }
    else { emit('saved'); emit('close') }
  } catch (e) {
    if (current()) error.value = e.message
  } finally {
    if (current() && !continued) busy.value = false
  }
}
function save() { return saveScene(false) }
function saveAndContinue() { return saveScene(true) }
watch(
  () => [props.show, props.conversationId, props.initialTab],
  () => {
    panelVersion++
    busy.value = false
    if (props.show) {tab.value=['scene','memory','album'].includes(props.initialTab)?props.initialTab:'scene';state.value=null;error.value='';deliveryRequest++;delivery.value={};deliveryLoading.value=false;load()}
  },
  { immediate: true, flush: 'sync' },
)
</script>
