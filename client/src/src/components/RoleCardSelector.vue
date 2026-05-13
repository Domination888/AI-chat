<template>
  <div class="relative">
    <!-- 顶部标题栏 -->
    <div class="sticky top-0 bg-white z-10 px-6 py-4 border-b border-gray-100 flex items-center justify-between">
      <h3 class="text-xl font-bold text-gray-800">选择角色</h3>
      <button 
        @click="$emit('close')"
        class="w-8 h-8 rounded-full flex items-center justify-center text-gray-400 hover:bg-gray-100 hover:text-gray-600 transition-all"
      >
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
        </svg>
      </button>
    </div>
    
    <!-- 搜索区域 -->
    <div class="px-6 py-4">
      <div class="relative">
        <svg class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
        </svg>
        <input 
          v-model="searchQuery" 
          type="text" 
          placeholder="搜索角色名称、描述或标签..."
          class="w-full pl-10 pr-4 py-3 bg-gray-50 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500/30 focus:border-blue-500 text-sm transition-all"
        />
      </div>
    </div>
    
    <!-- 角色卡片网格 -->
    <div class="px-6 pb-6 grid grid-cols-1 sm:grid-cols-2 md:grid-cols-2 gap-4 max-h-[65vh] overflow-y-auto pr-2">
      <div 
        v-for="role in filteredRoles" 
        :key="role.id"
        @click="selectRole(role)"
        class="group p-4 rounded-2xl border cursor-pointer transition-all duration-200 hover:shadow-md hover:scale-[1.02]"
        :class="[
          selectedRoleId === role.id 
            ? 'border-blue-500 bg-blue-50/50 shadow-sm' 
            : 'border-gray-200 bg-white hover:border-blue-300'
        ]"
      >
        <div class="flex items-start gap-4">
          <!-- 角色头像 -->
          <div class="relative flex-shrink-0">
            <div class="w-14 h-14 rounded-2xl bg-gradient-to-br from-pink-500 via-purple-500 to-indigo-500 flex items-center justify-center text-white font-bold text-xl shadow-md">
              {{ role.name.charAt(0) }}
            </div>
            <!-- 选中标记 -->
            <div v-if="selectedRoleId === role.id" class="absolute -top-1 -right-1 w-5 h-5 bg-blue-500 rounded-full flex items-center justify-center text-white">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="3" d="M5 13l4 4L19 7" />
              </svg>
            </div>
          </div>
          
          <!-- 角色信息 -->
          <div class="flex-1 min-w-0">
            <h4 class="font-semibold text-gray-900 text-base truncate">{{ role.name }}</h4>
            <p class="text-xs text-gray-500 mt-1.5 line-clamp-2 leading-relaxed">
              {{ role.description || '暂无角色描述，点击选择开始对话' }}
            </p>
            <div class="flex items-center flex-wrap gap-2 mt-2.5">
              <span v-if="role.voiceId" class="inline-flex items-center gap-1 text-xs bg-emerald-50 text-emerald-700 px-2.5 py-1 rounded-full font-medium">
                <svg class="h-3 w-3" fill="currentColor" viewBox="0 0 20 20">
                  <path fill-rule="evenodd" d="M9.383 3.076A1 1 0 0110 4v12a1 1 0 01-1.707.707L4.586 13H2a1 1 0 011-1V8a1 1 0 011-1h2.586l3.707-3.707a1 1 0 011.09-.217zM14.657 2.929a1 1 0 011.414 0A9.972 9.972 0 0119 10a9.972 9.972 0 01-2.929 7.071 1 1 0 01-1.414-1.414A7.971 7.971 0 0017 10c0-2.21-.894-4.208-2.343-5.657a1 1 0 010-1.414zm-2.829 2.828a1 1 0 011.415 0A5.983 5.983 0 0115 10a5.984 5.984 0 01-1.757 4.243 1 1 0 01-1.415-1.415A3.984 3.984 0 0013 10a3.983 3.983 0 00-1.172-2.828a1 1 0 010-1.415z" clip-rule="evenodd" />
                </svg>
                语音角色
              </span>
              <span v-if="role.tags" class="text-xs bg-blue-50 text-blue-700 px-2.5 py-1 rounded-full font-medium">
                {{ role.tags.split(',')[0] }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
    
    <!-- 空状态 -->
    <div v-if="filteredRoles.length === 0" class="text-center py-16 px-6">
      <div class="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
        </svg>
      </div>
      <p class="text-gray-500 text-sm">没有找到匹配的角色</p>
      <p class="text-gray-400 text-xs mt-1">试试更换搜索关键词</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  roles: {
    type: Array,
    default: () => []
  },
  selectedRoleId: {
    type: Number,
    default: null
  }
})

const emit = defineEmits(['select', 'close'])

const searchQuery = ref('')

const filteredRoles = computed(() => {
  if (!searchQuery.value.trim()) {
    return props.roles
  }
  const query = searchQuery.value.toLowerCase()
  return props.roles.filter(role => 
    role.name.toLowerCase().includes(query) || 
    (role.description && role.description.toLowerCase().includes(query)) ||
    (role.tags && role.tags.toLowerCase().includes(query))
  )
})

const selectRole = (role) => {
  emit('select', role)
}
</script>