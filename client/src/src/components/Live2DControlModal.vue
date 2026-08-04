<template>
  <div v-if="show" :class="embedded ? 'w-full' : 'fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50'">
    <div :class="embedded ? 'w-full' : 'bg-white dark:bg-gray-800 rounded-xl shadow-lg w-full max-w-2xl mx-4'">
      <div v-if="!embedded" class="flex items-center justify-between p-4 border-b dark:border-gray-700">
        <h3 class="text-lg font-semibold dark:text-gray-100">Live2D</h3>
        <button @click="$emit('close')" class="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div :class="embedded ? 'space-y-5' : 'p-4 space-y-4 max-h-[70vh] overflow-y-auto'">
        <div v-if="embedded">
          <h3 class="text-lg font-semibold dark:text-gray-100">Live2D 形象</h3>
          <p class="text-xs text-gray-500 dark:text-gray-400 mt-1">预览动作与表情，并调整桌面形象显示大小。</p>
        </div>
        <div>
          <div class="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">动作</div>
          <div class="grid grid-cols-3 sm:grid-cols-4 gap-2">
            <button
              v-for="motion in allLive2dMotions"
              :key="`${motion.group}-${motion.index}`"
              type="button"
              @click="playLive2dMotion(motion)"
              class="px-3 py-2 text-xs border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition"
            >
              {{ motion.name }}
            </button>
          </div>
        </div>
        <div>
          <div class="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">表情</div>
          <div class="grid grid-cols-3 sm:grid-cols-5 gap-2">
            <button
              v-for="expression in allLive2dExpressions"
              :key="expression"
              type="button"
              @click="playLive2dExpression(expression)"
              class="px-3 py-2 text-xs border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition"
            >
              {{ expression }}
            </button>
            <button
              type="button"
              @click="resetLive2dExpression"
              class="px-3 py-2 text-xs border border-amber-300 text-amber-700 dark:border-amber-700 dark:text-amber-300 rounded-md hover:bg-amber-50 dark:hover:bg-amber-950/40 transition"
            >
              重置
            </button>
          </div>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">模型大小</label>
          <input
            v-model.number="live2dScale"
            type="range"
            min="0.3"
            max="2.0"
            step="0.05"
            class="w-full"
            @input="setLive2dScale"
          />
          <div class="text-xs text-gray-500 dark:text-gray-400 text-center">{{ live2dScale.toFixed(2) }}x</div>
        </div>
      </div>

      <div v-if="!embedded" class="p-4 border-t dark:border-gray-700 flex justify-end">
        <button @click="$emit('close')" class="px-4 py-2 text-sm border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition">
          关闭
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { allLive2dExpressions, allLive2dMotions } from '../live2d/live2d-options.js'

defineProps({
  show: { type: Boolean, default: false },
  embedded: { type: Boolean, default: false }
})

defineEmits(['close'])

const live2dScale = ref(1.0)

const sendLive2dControl = (action, data = null) => {
  if (window.electronAPI?.live2dControl) {
    window.electronAPI.live2dControl(action, data)
  }
}

const playLive2dMotion = (motion) => {
  sendLive2dControl('motion', motion)
}

const playLive2dExpression = (name) => {
  sendLive2dControl('expression', { name })
}

const resetLive2dExpression = () => {
  sendLive2dControl('reset')
}

const setLive2dScale = () => {
  sendLive2dControl('scale', { scale: live2dScale.value })
}
</script>
