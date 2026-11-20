<template>
  <section class="story-memory">
    <p v-if="memories.length">修改或停止召回仅影响所选记忆。原始对话、场景和其他记忆会保留。</p>
    <button :disabled="busy || !conversationId" @click="loadMemory">{{ busy ? '处理中…' : '刷新记忆' }}</button>
    <p v-if="error" class="release-error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="!conversationId">先选择一个篇章。</p>
    <p v-else-if="!busy && !error && !memories.length">暂无记忆。</p>
    <article v-for="memory in memories" :key="memory.history_id" class="capability-memory">
      <small><time :datetime="memory.created_at">{{ formatRecordedTime(memory.created_at) }}</time></small>
      <p v-if="memory.index_status !== 'saved'" role="status">{{ memory.index_status === 'failed' ? '语义索引暂不可用' : '语义索引正在准备' }}，仍可编辑记忆。</p>
      <details>
        <summary>查看原始对话</summary>
        <p>你：{{ memory.user_text }}</p>
        <p>角色：{{ memory.assistant_text }}</p>
      </details>
      <label>修改记忆<textarea v-model="memory.correction" :placeholder="memory.content" rows="3" maxlength="2000" /></label>
      <label class="release-check"><input type="checkbox" v-model="memory.hidden" />停止召回这条记忆</label>
      <button :disabled="busy" @click="saveMemory(memory)">保存记忆</button>
    </article>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import { json } from './api.mjs'
import { formatRecordedTime } from './recordedTime.mjs'

const props = defineProps({ conversationId: String })
const busy = ref(false), error = ref(''), notice = ref(''), memories = ref([])

async function run(action) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try { await action() }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}

async function loadMemory() {
  await run(async () => {
    memories.value = props.conversationId
      ? (await json(`/api/memory?conversationId=${props.conversationId}`)).map(memory => ({ ...memory, hidden: !!memory.hidden }))
      : []
  })
}

async function saveMemory(memory) {
  await run(async () => {
    await json(`/api/memory/${memory.history_id}`, {
      conversationId: props.conversationId,
      correction: memory.correction,
      hidden: memory.hidden,
    }, 'PUT')
    notice.value = '记忆已更新'
  })
}

watch(() => props.conversationId, loadMemory, { immediate: true })
</script>
