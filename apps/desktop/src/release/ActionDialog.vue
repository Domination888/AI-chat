<template>
  <div class="release-modal-backdrop action-backdrop" @click.self="cancel">
    <form ref="dialog" class="release-modal action-dialog" role="dialog" aria-modal="true"
      aria-labelledby="action-title" aria-describedby="action-description" @submit.prevent="submit" @keydown="keydown">
      <header><h2 id="action-title">{{ options.title }}</h2></header>
      <p id="action-description">{{ options.description }}</p>
      <label v-if="options.input">修改后的消息<textarea ref="input" v-model="value" rows="5" maxlength="6000" required /></label>
      <blockquote v-else-if="options.preview">{{ options.preview }}</blockquote>
      <div class="release-button-row">
        <button ref="cancelButton" type="button" @click="cancel">取消</button>
        <button class="release-primary" type="submit" :disabled="options.input && !value.trim()">{{ options.confirmLabel || '确认' }}</button>
      </div>
    </form>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
const props = defineProps({ options: { type: Object, required: true } })
const emit = defineEmits(['resolve'])
const value = ref(props.options.value || '')
const dialog = ref(null), input = ref(null), cancelButton = ref(null)
let previousFocus = null
const cancel = () => emit('resolve', null)
function submit() {
  if (props.options.input && !value.value.trim()) return
  emit('resolve', props.options.input ? value.value : true)
}
function keydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); cancel(); return }
  if (event.key !== 'Tab') return
  event.stopPropagation()
  const items = [...dialog.value.querySelectorAll('button:not(:disabled),textarea:not(:disabled)')]
  const first = items[0], last = items.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
onMounted(async () => {
  previousFocus = document.activeElement
  await nextTick()
  ;(input.value || cancelButton.value)?.focus()
})
onBeforeUnmount(() => {
  if (previousFocus?.isConnected) previousFocus.focus()
})
</script>

<style scoped>
.action-backdrop {z-index:110}
.action-dialog {max-width:560px}
blockquote {margin:18px 0;white-space:pre-wrap;overflow-wrap:anywhere;max-height:160px;overflow:auto}
.release-button-row {justify-content:flex-end;margin-bottom:0}
</style>
