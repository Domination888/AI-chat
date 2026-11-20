<template>
  <div ref="container" class="message-viewport no-scrollbar" @scroll="$emit('scroll', $event)">
    <div class="message-column">
      <button v-if="hasOlder" class="load-older" @click="$emit('older')">↑ 阅读更早的对话</button>
      <section v-if="!messages.length && searchQuery" class="story-welcome" role="status">
        <h2>没有找到匹配的对话</h2>
        <p>关键词「{{ searchQuery }}」没有匹配结果。</p>
        <div class="prompt-list"><button @click="$emit('clear-search')">清除搜索</button></div>
      </section>
      <section v-else-if="!messages.length" class="story-welcome">
        <h2>还没有消息</h2>
        <div class="prompt-list">
          <button @click="$emit('prompt', '今天，想和你一起做点不一样的事。')">聊聊今天</button>
          <button @click="$emit('prompt', '如果我们要从一个全新的场景开始，你会选择哪里？')">换个场景</button>
          <button @click="$emit('prompt', '你还记得我们之间最重要的约定吗？')">聊聊之前的约定</button>
        </div>
      </section>
      <div v-for="(msg, index) in messages" :key="msg.id || index" class="message-row" :class="{ 'is-user': msg.role === 'user' }">
        <div class="message-avatar"><img v-if="msg.role !== 'user' && selectedRole?.storyPortrait" :src="selectedRole.storyPortrait" :alt="selectedRole.name"/><template v-else>{{ msg.role === 'user' ? '我' : selectedRole?.name?.charAt(0) || '角' }}</template></div>
        <div class="message-body">
          <div class="message-name">{{ msg.role === 'user' ? '你' : selectedRole?.name || '角色' }}<span v-if="msg.scheduledSkill"> · 定时提醒（{{ msg.scheduledSkill }}）</span><span v-else-if="msg.isProactive"> · 主动来信</span></div>
          <div class="message-bubble">
            <div v-if="msg.images?.length" class="message-images"><img v-for="(img, idx) in msg.images" :key="idx" :src="img" alt="对话图片" /></div>
            <div v-if="msg.isAudio" class="audio-note">◉ 正在识别语音</div>
            <div class="message-text">{{ msg.content }}<span v-if="!msg.content && msg.status === 'failed'">这次回复未能完成。请检查模型连接后点击重试。</span><span v-else-if="!msg.content && msg.status === 'interrupted'">回复已停止，你可以重试。</span><span v-if="loading && index === messages.length - 1 && !msg.content && msg.role === 'ai'" class="typing-indicator">▋</span></div>
            <p v-if="msg.dailyIssueDate" class="message-tool-note">AI 日报 · {{ msg.dailyIssueDate }} 期</p>
            <p v-if="msg.completion?.incomplete && msg.completion.reason==='length'" class="message-tool-note" role="status">回复达到长度上限，内容尚未完成。已保留现有内容，可以继续回复。</p>
            <div v-if="msg.searchStatus" class="search-note">⌕ {{ msg.searchStatus }}</div>
            <div v-if="msg.toolCalls?.length" class="message-tool-traces" aria-label="实际工具调用记录"><details v-for="call in msg.toolCalls" :key="call.id"><summary>{{ call.serverName || call.name }}{{ call.serverName ? ` / ${call.name}` : '' }} · {{ toolPhaseLabel(call.phase) }}</summary><pre v-if="call.arguments">参数：{{ JSON.stringify(call.arguments,null,2) }}</pre><pre v-if="call.phase==='succeeded'">结果摘要：{{ call.result || '工具已执行，未返回文字结果。' }}</pre><p v-if="call.phase==='failed'">{{ call.error }}</p></details></div>
            <p v-if="msg.toolPlanning?.phase==='not_called'" class="message-tool-note">本轮模型未调用已授权的工具。</p>
            <p v-else-if="msg.toolPlanning?.phase==='failed'" class="message-tool-note">{{ msg.toolPlanning.message || '工具流程未完成，请检查配置或拆分任务后重试。' }}</p>
            <div v-if="msg.sources?.length" class="message-sources"><span>参考来源</span><a v-for="(source, sourceIndex) in msg.sources" :key="source.url || sourceIndex" :href="source.url" @click="externalLinks && ($event.preventDefault(), $emit('source',source.url))" target="_blank" rel="noopener noreferrer">{{ sourceIndex + 1 }}. {{ source.title || source.url }}</a></div>
          </div>
          <div v-if="msg.candidateId && !msg.feedback" class="message-feedback"><button @click="$emit('feedback', msg, 'interested')">喜欢这类话题</button><button @click="$emit('feedback', msg, 'less_like')">少聊这类话题</button></div>
          <div v-else-if="msg.feedback" class="message-meta">已反馈：{{ msg.feedback === 'interested' ? '感兴趣' : '少推此类' }}</div>
          <div v-if="msg.id && !loading" class="message-actions">
            <button @click="$emit('branch', msg)">分支</button><button @click="$emit('rewind', msg)">回退</button>
            <button v-if="msg.role === 'user'" @click="$emit('edit', msg)">编辑</button><button v-if="regenerationAction(messages, index)" @click="$emit('regenerate', index)">{{ regenerationAction(messages, index).label }}</button><button v-if="manualContinuationAvailable(msg)" @click="$emit('continue-reply', index)">继续回复</button>
            <button v-if="msg.turnId" @click="$emit('bookmark', msg)">{{ msg.bookmarked ? '取消收藏' : '收藏' }}</button>
            <button v-if="msg.audioUrl && msg.role === 'ai'" @click="$emit('play', msg.audioUrl)">▶ 播放语音</button>
            <span v-if="msg.status && msg.status !== 'complete'">{{ msg.completion?.reason==='length' ? '达到长度上限' : msg.status === 'interrupted' ? '已打断' : '未完成' }}</span>
          </div>
          <button v-else-if="msg.audioUrl && msg.role === 'ai'" class="message-audio" @click="$emit('play', msg.audioUrl)">▶ 播放语音</button>
        </div>
      </div>
    </div>
  </div>
</template>
<script setup>
import { ref } from 'vue'
import { regenerationAction, manualContinuationAvailable } from '../../release/messageActions.mjs'
import {toolPhaseLabel} from '../../release/toolTrace.mjs'
defineProps({ messages: { type: Array, default: () => [] }, selectedRole: Object, searchQuery: { type: String, default: '' }, hasOlder: Boolean, loading: Boolean, externalLinks: Boolean })
defineEmits(['scroll', 'older', 'branch', 'rewind', 'edit', 'regenerate', 'continue-reply', 'bookmark', 'play', 'feedback', 'prompt', 'source', 'clear-search'])
const container = ref(null)
defineExpose({ element: () => container.value })
</script>
<style scoped>
.message-tool-traces{display:grid;gap:8px;margin-top:12px;border-top:1px solid var(--r-line,#d7dfd1);padding-top:10px}
.message-tool-traces summary{cursor:pointer;font-size:12px;line-height:1.7;overflow-wrap:anywhere}
.message-tool-traces pre{font:12px/1.7 ui-monospace,monospace;white-space:pre-wrap;overflow-wrap:anywhere;max-height:220px;overflow:auto;margin:8px 0;background:#f0f4eb;padding:10px;border-radius:8px}
.message-tool-traces p,.message-tool-note{font-size:12px;line-height:1.7;color:var(--r-muted,#66755d);margin:8px 0 0}
</style>
