<template>
  <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
    <div class="bg-white dark:bg-gray-800 rounded-xl shadow-2xl w-full max-w-6xl mx-3 sm:mx-6 h-[min(90vh,820px)] flex flex-col overflow-hidden">
      <div class="flex items-center justify-between px-5 py-4 border-b dark:border-gray-700 shrink-0">
        <div>
          <h3 class="text-lg font-semibold dark:text-gray-100">设置中心</h3>
          <p class="text-xs text-gray-500 dark:text-gray-400 mt-0.5">模型、记忆、形象与系统能力统一管理</p>
        </div>
        <button @click="$emit('close')" class="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="sm:hidden flex gap-1 px-3 py-2 border-b dark:border-gray-700 overflow-x-auto shrink-0">
        <button
          v-for="tab in settingsTabs"
          :key="tab.key"
          type="button"
          @click="activeTab = tab.key"
          class="shrink-0 px-3 py-1.5 rounded-lg text-sm transition"
          :class="activeTab === tab.key
            ? 'bg-blue-600 text-white'
            : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700'"
        >
          {{ tab.label }}
        </button>
      </div>

      <div class="flex flex-1 min-h-0">
        <aside class="hidden sm:flex w-52 shrink-0 border-r dark:border-gray-700 bg-gray-50/80 dark:bg-gray-900/30 p-3 flex-col gap-1 overflow-y-auto">
          <button
            v-for="tab in settingsTabs"
            :key="tab.key"
            type="button"
            @click="activeTab = tab.key"
            class="w-full flex items-start gap-3 px-3 py-2.5 rounded-lg text-left transition"
            :class="activeTab === tab.key
              ? 'bg-blue-600 text-white shadow-sm'
              : 'text-gray-700 dark:text-gray-300 hover:bg-white dark:hover:bg-gray-700'"
          >
            <span class="text-base leading-5">{{ tab.icon }}</span>
            <span class="min-w-0">
              <span class="block text-sm font-medium">{{ tab.label }}</span>
              <span class="block text-[11px] mt-0.5 truncate" :class="activeTab === tab.key ? 'text-blue-100' : 'text-gray-400'">{{ tab.description }}</span>
            </span>
          </button>
        </aside>

        <div class="flex-1 min-w-0 p-4 sm:p-6 space-y-4 overflow-y-auto">
        <!-- LLM -->
        <div v-show="activeTab === 'models'" class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">主对话模型</h4>
          <div class="grid grid-cols-1 gap-3">
            <div v-if="form.recentLlmModels.length" class="space-y-2">
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300">历史模型</label>
              <select
                v-model="recentModelSelection"
                @change="selectRecentLlmModelByIndex"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm"
              >
                <option value="">选择历史模型</option>
                <option v-for="(model, index) in form.recentLlmModels" :key="`${model.modelName}-${model.baseUrl}`" :value="String(index)">
                  {{ model.modelName }} · {{ model.baseUrl }}
                </option>
              </select>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Base URL</label>
              <input v-model="form.modelBaseUrl" type="text" placeholder="http://127.0.0.1:1234/v1"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">API Key（可选）</label>
              <input v-model="form.llmApiKey" type="password" autocomplete="off" placeholder="本地模型可留空"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              <div class="mt-1 text-xs text-gray-500 dark:text-gray-400">接入外部 OpenAI 兼容 API 时填写；密钥仅发送到本机后端。</div>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">思考模式</label>
                <select v-model="form.llmThinkingMode"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm">
                  <option value="auto">跟随 API 默认</option>
                  <option value="enabled">开启</option>
                  <option value="disabled">关闭</option>
                </select>
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">思考强度</label>
                <select v-model="form.llmReasoningEffort" :disabled="form.llmThinkingMode === 'disabled'"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm disabled:opacity-50">
                  <option value="auto">跟随 API 默认</option>
                  <option value="low">low</option>
                  <option value="high">high</option>
                  <option value="xhigh">xhigh</option>
                  <option value="max">max</option>
                </select>
              </div>
            </div>
            <div class="text-xs text-gray-500 dark:text-gray-400">
              DeepSeek 默认开启思考且强度为 high；思考内容用于维持工具调用链，不会直接展示在聊天正文中。
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">模型名称</label>
                <input v-model="form.modelName" type="text" placeholder="gemma4-e4b"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">流式模型</label>
                <input v-model="form.llmStreamingModelName" type="text" placeholder="同左或留空"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">读超时 (ms)</label>
              <input v-model.number="form.llmReadTimeoutMs" type="number" min="5000" step="1000"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
          </div>
        </div>

        <!-- Utility LLM -->
        <div v-show="activeTab === 'models'" class="border dark:border-gray-700 rounded-lg p-3">
          <div class="flex items-center justify-between gap-3 mb-3">
            <div>
              <h4 class="font-semibold dark:text-gray-100">辅助 / 总结模型</h4>
              <div class="text-xs text-gray-500 dark:text-gray-400">用于话题判断、兴趣提取、搜索规划等内部任务</div>
            </div>
            <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400 shrink-0">
              <input v-model="form.utilityInheritConnection" type="checkbox" class="rounded border-gray-300" />
              继承主连接
            </label>
          </div>
          <div class="grid grid-cols-1 gap-3">
            <div v-if="!form.utilityInheritConnection" class="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Base URL</label>
                <input v-model="form.utilityBaseUrl" type="text" placeholder="OpenAI 兼容地址"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">API Key（可选）</label>
                <input v-model="form.utilityApiKey" type="password" autocomplete="off" placeholder="本地模型可留空"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">模型名称</label>
                <input v-model="form.utilityModelName" type="text" placeholder="留空继承主模型"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">思考模式</label>
                <select v-model="form.utilityThinkingMode" class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm">
                  <option value="disabled">关闭（推荐）</option><option value="auto">跟随 API</option><option value="enabled">开启</option>
                </select>
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">思考强度</label>
                <select v-model="form.utilityReasoningEffort" :disabled="form.utilityThinkingMode === 'disabled'"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm disabled:opacity-50">
                  <option value="auto">跟随 API</option><option value="low">low</option><option value="high">high</option><option value="xhigh">xhigh</option><option value="max">max</option>
                </select>
              </div>
            </div>
          </div>
        </div>

        <!-- Embedding -->
        <div v-show="activeTab === 'models'" class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">Embedding（RAG 向量）</h4>
          <div class="grid grid-cols-1 gap-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Base URL</label>
              <input v-model="form.embeddingBaseUrl" type="text" placeholder="http://127.0.0.1:1234/v1"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">模型名称</label>
              <input v-model="form.embeddingModelName" type="text" placeholder="text-embedding-qwen3-embedding-4b"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">API Key（可选）</label>
              <input v-model="form.embeddingApiKey" type="password" autocomplete="off" placeholder="本地 Embedding 可留空"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div class="flex items-center justify-between gap-3 pt-1">
              <div class="min-w-0">
                <div class="text-sm font-medium text-gray-700 dark:text-gray-300">RAG 索引</div>
                <div class="text-xs text-gray-500 dark:text-gray-400 truncate">语料或 Embedding 变更后手动重建</div>
              </div>
              <button
                type="button"
                @click="rebuildRagIndex"
                :disabled="rebuildingRag"
                class="shrink-0 px-3 py-2 text-sm border border-blue-500 text-blue-600 dark:text-blue-400 rounded-md hover:bg-blue-50 dark:hover:bg-blue-900/20 transition disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {{ rebuildingRag ? '重建中...' : '重建 RAG 索引' }}
              </button>
            </div>
          </div>
        </div>

        <!-- MemOS models -->
        <div v-show="activeTab === 'models'" class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-1">MemOS 记忆模型</h4>
          <div class="text-xs text-amber-600 dark:text-amber-400 mb-3">由独立 Python 进程使用，修改后需重启应用。</div>
          <div class="space-y-4">
            <div class="space-y-3">
              <div class="flex items-center justify-between gap-3">
                <div class="text-sm font-medium text-gray-700 dark:text-gray-300">记忆提取 / 总结</div>
                <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
                  <input v-model="form.memosModelInheritConnection" type="checkbox" class="rounded border-gray-300" />
                  继承辅助模型连接
                </label>
              </div>
              <div v-if="!form.memosModelInheritConnection" class="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <input v-model="form.memosModelBaseUrl" type="text" placeholder="Base URL"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
                <input v-model="form.memosModelApiKey" type="password" autocomplete="off" placeholder="API Key（可选）"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <input v-model="form.memosModelName" type="text" placeholder="模型名称；留空继承辅助模型"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div class="border-t dark:border-gray-700 pt-4 space-y-3">
              <div class="flex items-center justify-between gap-3">
                <div class="text-sm font-medium text-gray-700 dark:text-gray-300">记忆 Embedding</div>
                <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
                  <input v-model="form.memosEmbeddingInheritConnection" type="checkbox" class="rounded border-gray-300" />
                  继承 RAG Embedding
                </label>
              </div>
              <div v-if="!form.memosEmbeddingInheritConnection" class="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <input v-model="form.memosEmbeddingBaseUrl" type="text" placeholder="Base URL"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
                <input v-model="form.memosEmbeddingApiKey" type="password" autocomplete="off" placeholder="API Key（可选）"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <input v-model="form.memosEmbeddingModelName" type="text" placeholder="模型名称；留空继承 RAG Embedding"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
                <label class="text-xs text-gray-500 dark:text-gray-400">向量维度
                  <input v-model.number="form.memosEmbeddingDimension" type="number" min="1" step="1"
                    class="mt-1 w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
                </label>
              </div>
            </div>
          </div>
        </div>

        <!-- ASR -->
        <div v-show="activeTab === 'voice'" class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">语音识别 (ASR)</h4>
          <div class="grid grid-cols-1 gap-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">ASR 接口 URL</label>
              <input v-model="form.asrUrl" type="text" placeholder="http://127.0.0.1:9000/v1/audio/transcriptions"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">语言</label>
                <select v-model="form.asrLanguage" class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm">
                  <option value="zh">中文</option>
                  <option value="en">英文</option>
                  <option value="auto">自动检测</option>
                </select>
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">超时 (ms)</label>
                <input v-model.number="form.asrTimeoutMs" type="number" min="3000" step="1000"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
            </div>
          </div>
        </div>

        <!-- TTS -->
        <div v-show="activeTab === 'voice'" class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">语音合成 (TTS)</h4>
          <div class="grid grid-cols-1 gap-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Astra TTS 服务 URL</label>
              <input v-model="form.astraTtsBaseUrl" @blur="loadTtsAvatars" type="text" placeholder="http://192.168.x.x:5000"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div>
              <div class="flex items-center justify-between gap-3 mb-1">
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300">已注册 TTS 音色</label>
                <button
                  type="button"
                  @click="loadTtsAvatars"
                  :disabled="loadingTtsAvatars || !form.astraTtsBaseUrl"
                  class="px-2.5 py-1 text-xs border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {{ loadingTtsAvatars ? '刷新中...' : '刷新' }}
                </button>
              </div>
              <select
                v-model="form.astraDefaultAvatarId"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm"
              >
                <option value="">自动选择</option>
                <option
                  v-for="avatar in ttsAvatars"
                  :key="avatar.id"
                  :value="avatar.id"
                >
                  {{ avatar.name ? `${avatar.name} (${avatar.id})` : avatar.id }}
                </option>
              </select>
              <div v-if="ttsAvatarError" class="mt-1 text-xs text-red-500">{{ ttsAvatarError }}</div>
              <div v-else-if="ttsAvatars.length" class="mt-1 text-xs text-gray-500 dark:text-gray-400">
                共 {{ ttsAvatars.length }} 个音色；当前选择会优先于自动匹配和角色配置。
              </div>
              <div v-else class="mt-1 text-xs text-gray-500 dark:text-gray-400">
                点击刷新读取当前 TTS 服务的音色列表。
              </div>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">当前 Avatar ID</label>
                <input v-model="form.astraDefaultAvatarId" type="text" placeholder="留空自动选择"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">默认音色 Profile</label>
                <input v-model="form.ttsDefaultProfile" type="text" placeholder="shu"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">流式分片 (Token)</label>
                <input v-model.number="form.astraStreamingChunkSize" type="number" min="4" max="128" step="1"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">TTS 超时 (ms)</label>
                <input v-model.number="form.ttsTimeoutMs" type="number" min="10000" step="5000"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">语速（前端覆盖）</label>
              <input v-model.number="form.ttsSpeed" type="range" min="0.5" max="2.0" step="0.1" class="w-full" />
              <div class="text-xs text-gray-500 dark:text-gray-400 text-center">{{ form.ttsSpeed }}x</div>
            </div>
          </div>
        </div>

        <!-- Memos -->
        <div v-show="activeTab === 'memory'" class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">记忆服务 (Memos)</h4>
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600 dark:text-gray-400">启用 Memos</span>
              <button @click="form.memosEnabled = !form.memosEnabled"
                class="relative w-10 h-5 rounded-full transition-colors"
                :class="form.memosEnabled ? 'bg-blue-500' : 'bg-gray-300'">
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                      :class="form.memosEnabled ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Memos Base URL</label>
              <input v-model="form.memosBaseUrl" type="text" placeholder="http://localhost:8000"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">搜索 Top K</label>
                <input v-model.number="form.memosSearchTopK" type="number" min="1" max="50"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">搜索模式</label>
                <select v-model="form.memosSearchMode" class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm">
                  <option value="fast">fast</option>
                  <option value="fine">fine</option>
                  <option value="mixture">mixture</option>
                </select>
              </div>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">相关性阈值</label>
                <input v-model.number="form.memosRelativity" type="number" min="0" max="1" step="0.05"
                  class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">去重策略</label>
                <select v-model="form.memosDedup" class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm">
                  <option value="mmr">mmr</option>
                  <option value="sim">sim</option>
                  <option value="no">no</option>
                </select>
              </div>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
                <input v-model="form.memosIncludePreference" type="checkbox" class="rounded border-gray-300" />
                偏好记忆
              </label>
              <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
                <input v-model="form.memosSaveAssistantTurns" type="checkbox" class="rounded border-gray-300" />
                保存助手回复
              </label>
              <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
                <input v-model="form.memosSearchToolMemory" type="checkbox" class="rounded border-gray-300" />
                工具记忆
              </label>
              <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
                <input v-model="form.memosIncludeSkillMemory" type="checkbox" class="rounded border-gray-300" />
                技能记忆
              </label>
            </div>
          </div>
        </div>

        <!-- 本地 Search-RAG -->
        <div v-show="activeTab === 'search'" class="border dark:border-gray-700 rounded-lg p-3">
          <div class="flex items-center justify-between mb-3">
            <h4 class="font-semibold dark:text-gray-100">本地联网研究</h4>
            <span class="text-xs" :class="searchHealth?.available ? 'text-emerald-500' : 'text-amber-500'">
              {{ searchHealth == null ? '尚未检测' : (searchHealth.available ? 'SearXNG 可用' : 'SearXNG 不可用') }}
            </span>
          </div>
          <div class="space-y-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">SearXNG URL</label>
              <input v-model="form.searxngUrl" type="text" placeholder="http://127.0.0.1:8888"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
            </div>
            <div class="grid grid-cols-3 gap-3">
              <label class="text-xs text-gray-600 dark:text-gray-400">查询数
                <input v-model.number="form.searchMaxQueries" type="number" min="1" max="3" class="mt-1 w-full px-2 py-1 border rounded dark:bg-gray-700 dark:border-gray-600" />
              </label>
              <label class="text-xs text-gray-600 dark:text-gray-400">读取网页
                <input v-model.number="form.searchFetchPages" type="number" min="1" max="8" class="mt-1 w-full px-2 py-1 border rounded dark:bg-gray-700 dark:border-gray-600" />
              </label>
              <label class="text-xs text-gray-600 dark:text-gray-400">最终来源
                <input v-model.number="form.searchMaxSources" type="number" min="1" max="5" class="mt-1 w-full px-2 py-1 border rounded dark:bg-gray-700 dark:border-gray-600" />
              </label>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">搜索引擎</label>
              <input v-model="form.searchEngines" type="text" placeholder="brave,duckduckgo,bing,baidu,sogou,360search"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              <div v-if="engineStats.length" class="mt-2 grid grid-cols-2 sm:grid-cols-3 gap-1 text-xs text-gray-500 dark:text-gray-400">
                <span v-for="engine in engineStats" :key="engine.name">
                  {{ engine.name }}：{{ engine.rate }}%（{{ engine.success }}/{{ engine.total }}）
                </span>
              </div>
            </div>
            <label class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
              <input v-model="form.searchQueryPlannerEnabled" type="checkbox" class="rounded border-gray-300" />
              使用本地 LLM 规划搜索查询
            </label>
            <div class="flex gap-2">
              <input v-model="searchTestQuery" type="text" placeholder="输入测试问题"
                class="flex-1 px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm" />
              <button type="button" @click="testSearch" :disabled="testingSearch"
                class="px-3 py-2 text-sm border border-emerald-500 text-emerald-600 rounded-md disabled:opacity-50">
                {{ testingSearch ? '检索中...' : '测试' }}
              </button>
            </div>
            <div v-if="searchTestResult" class="text-xs text-gray-500 dark:text-gray-400 whitespace-pre-wrap">{{ searchTestResult }}</div>
          </div>
        </div>

        <!-- 主动说话 -->
        <div v-show="activeTab === 'proactive'" class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">主动说话</h4>
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600 dark:text-gray-400">启用主动搭话</span>
              <button @click="form.proactiveChatEnabled = !form.proactiveChatEnabled"
                class="relative w-10 h-5 rounded-full transition-colors"
                :class="form.proactiveChatEnabled ? 'bg-blue-500' : 'bg-gray-300'">
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                      :class="form.proactiveChatEnabled ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
            <div v-if="form.proactiveChatEnabled">
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">搭话间隔</label>
              <input v-model.number="form.proactiveIdleSeconds" type="range"
                :min="PROACTIVE_IDLE_MIN" :max="PROACTIVE_IDLE_MAX" :step="PROACTIVE_IDLE_STEP" class="w-full" />
              <div class="text-xs text-gray-500 dark:text-gray-400 text-center">{{ formatProactiveIdle(form.proactiveIdleSeconds) }}</div>
            </div>
            <div v-if="form.proactiveChatEnabled">
              <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">搭话提示词</label>
              <textarea v-model="form.proactivePrompt" rows="2"
                class="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100 rounded-md text-sm resize-none"></textarea>
            </div>
            <div v-if="form.proactiveChatEnabled" class="border-t dark:border-gray-700 pt-3 space-y-3">
              <div class="flex items-center justify-between">
                <div>
                  <div class="text-sm text-gray-700 dark:text-gray-300">提前准备感兴趣的新话题</div>
                  <div class="text-xs text-gray-500">关闭时不后台搜索；话题结束后仍会按需联网</div>
                </div>
                <button @click="form.autoResearchEnabled = !form.autoResearchEnabled"
                  class="relative w-10 h-5 rounded-full transition-colors"
                  :class="form.autoResearchEnabled ? 'bg-emerald-500' : 'bg-gray-300'">
                  <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                        :class="form.autoResearchEnabled ? 'left-[22px]' : 'left-0.5'"></span>
                </button>
              </div>
              <div v-if="form.autoResearchEnabled" class="grid grid-cols-2 gap-2">
                <label class="text-xs text-gray-500">搜索间隔(分钟)<input v-model.number="form.researchIntervalMinutes" type="number" min="30" class="mt-1 w-full px-2 py-1 border rounded dark:bg-gray-700 dark:border-gray-600" /></label>
                <label class="text-xs text-gray-500">冷却(分钟)<input v-model.number="form.researchCooldownMinutes" type="number" min="30" class="mt-1 w-full px-2 py-1 border rounded dark:bg-gray-700 dark:border-gray-600" /></label>
              </div>
              <div v-if="form.autoResearchEnabled" class="grid grid-cols-2 gap-2">
                <label class="text-xs text-gray-500">静默开始<input v-model="form.researchQuietStart" type="time" class="mt-1 w-full px-2 py-1 border rounded dark:bg-gray-700 dark:border-gray-600" /></label>
                <label class="text-xs text-gray-500">静默结束<input v-model="form.researchQuietEnd" type="time" class="mt-1 w-full px-2 py-1 border rounded dark:bg-gray-700 dark:border-gray-600" /></label>
              </div>
              <div v-if="props.userId" class="space-y-2">
                <div class="flex gap-2">
                  <input v-model="newInterest" @keydown.enter.prevent="addInterest" placeholder="手动添加兴趣"
                    class="flex-1 px-2 py-1 border rounded text-sm dark:bg-gray-700 dark:border-gray-600" />
                  <button type="button" @click="addInterest" class="px-2 py-1 text-xs border rounded">添加</button>
                  <button type="button" @click="refreshInterests" class="px-2 py-1 text-xs border rounded">重新推断</button>
                </div>
                <div v-for="interest in interests" :key="interest.id" class="flex items-center gap-2 text-xs">
                  <button type="button" @click="toggleInterest(interest)" :class="interest.enabled ? 'text-emerald-500' : 'text-gray-400'">{{ interest.enabled ? '●' : '○' }}</button>
                  <span class="flex-1 truncate" :title="interest.evidence">{{ interest.topic }}</span>
                  <span class="text-gray-400">{{ interest.source === 'manual' ? '手动' : '推断' }}</span>
                  <button type="button" @click="deleteInterest(interest)" class="text-red-400">删除</button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 其他 -->
        <div v-show="activeTab === 'general'" class="border dark:border-gray-700 rounded-lg p-3">
          <h4 class="font-semibold dark:text-gray-100 mb-3">其他</h4>
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600 dark:text-gray-400">自动播放语音</span>
              <button @click="form.autoPlayTts = !form.autoPlayTts"
                class="relative w-10 h-5 rounded-full transition-colors"
                :class="form.autoPlayTts ? 'bg-blue-500' : 'bg-gray-300'">
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                      :class="form.autoPlayTts ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
            <div class="flex items-center justify-between">
              <span class="text-sm text-gray-600 dark:text-gray-400">深色模式</span>
              <button @click="form.darkMode = !form.darkMode"
                class="relative w-10 h-5 rounded-full transition-colors"
                :class="form.darkMode ? 'bg-blue-500' : 'bg-gray-300'">
                <span class="absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                      :class="form.darkMode ? 'left-[22px]' : 'left-0.5'"></span>
              </button>
            </div>
          </div>
        </div>

        <MemoryManagerModal
          embedded
          :show="activeTab === 'memoryData'"
          :memos-enabled="form.memosEnabled"
          :current-role-id="currentRoleId"
        />

        <Live2DControlModal embedded :show="activeTab === 'appearance'" />

        <McpSkillManager embedded :show="activeTab === 'extensions'" />

        <LogViewerModal v-if="isElectron" embedded :show="activeTab === 'diagnostics'" />

        <p v-if="configTabKeys.has(activeTab)" class="text-xs text-gray-400 dark:text-gray-500">
          配置会写入 config/runtime-config.json。应用内模型立即生效；MemOS 内部模型需重启应用后生效。
        </p>
        </div>
      </div>
      
      <div class="px-5 py-3 border-t dark:border-gray-700 flex items-center justify-between gap-2 shrink-0 bg-white dark:bg-gray-800">
        <span class="hidden sm:block text-xs text-gray-400">{{ activeTabMeta?.description }}</span>
        <div class="flex justify-end gap-2 ml-auto">
        <button @click="$emit('close')" class="px-4 py-2 text-sm border border-gray-300 dark:border-gray-600 dark:text-gray-300 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700 transition">
          关闭
        </button>
        <button @click="saveSettings" :disabled="saving" class="px-4 py-2 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700 transition disabled:opacity-50">
          {{ saving ? '保存中...' : '保存设置' }}
        </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, reactive, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  DEFAULT_SETTINGS,
  fetchRuntimeConfig,
  rememberRecentLlmModel,
  runtimeConfigToSettings,
  saveRuntimeConfig
} from '../utils/runtimeConfig.js'
import { apiFetch } from '../utils/api.js'
import MemoryManagerModal from './MemoryManagerModal.vue'
import Live2DControlModal from './Live2DControlModal.vue'
import LogViewerModal from './LogViewerModal.vue'
import McpSkillManager from './McpSkillManager.vue'

const props = defineProps({
  show: { type: Boolean, default: false },
  initialSettings: { type: Object, default: () => ({}) },
  userId: { type: Number, default: null },
  currentRoleId: { type: [Number, String], default: null },
  isElectron: { type: Boolean, default: false }
})

const emit = defineEmits(['close', 'save'])

const form = reactive({ ...DEFAULT_SETTINGS })
const settingsTabs = computed(() => [
  { key: 'models', label: '模型', icon: '◈', description: '对话、辅助与向量模型' },
  { key: 'memory', label: '记忆服务', icon: '◎', description: '检索与写入策略' },
  { key: 'memoryData', label: '记忆数据', icon: '▤', description: '查看、纠错与删除' },
  { key: 'voice', label: '语音', icon: '◖', description: '识别与语音合成' },
  { key: 'appearance', label: 'Live2D 形象', icon: '◇', description: '动作、表情与大小' },
  { key: 'search', label: '联网研究', icon: '⌁', description: '搜索引擎与检索参数' },
  { key: 'extensions', label: '扩展与技能', icon: '⊞', description: 'MCP、工具与技能' },
  { key: 'proactive', label: '主动能力', icon: '✦', description: '搭话与兴趣研究' },
  { key: 'general', label: '通用', icon: '⚙', description: '界面与播放偏好' },
  props.isElectron ? { key: 'diagnostics', label: '系统日志', icon: '⌘', description: '服务状态与运行日志' } : null
].filter(Boolean))
const configTabKeys = new Set(['models', 'memory', 'voice', 'search', 'proactive', 'general'])
const activeTab = ref('models')
const activeTabMeta = computed(() => settingsTabs.value.find(tab => tab.key === activeTab.value))
const recentModelSelection = ref('')
const saving = ref(false)
const rebuildingRag = ref(false)
const loadingTtsAvatars = ref(false)
const ttsAvatarError = ref('')
const ttsAvatars = ref([])
const searchHealth = ref(null)
const searchTestQuery = ref('OpenAI 最新模型')
const searchTestResult = ref('')
const testingSearch = ref(false)
const engineStats = computed(() => {
  const successes = searchHealth.value?.engineSuccesses || {}
  const failures = searchHealth.value?.engineFailures || {}
  return [...new Set([...Object.keys(successes), ...Object.keys(failures)])]
    .sort()
    .map((name) => {
      const success = Number(successes[name] || 0)
      const total = success + Number(failures[name] || 0)
      return { name, success, total, rate: total ? Math.round(success * 100 / total) : 0 }
    })
})
const interests = ref([])
const newInterest = ref('')

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

const formatProactiveIdle = (seconds) => {
  const hours = Math.floor(seconds / 3600)
  const minutes = (seconds % 3600) / 60
  if (hours === 0) return `${minutes}分钟`
  if (minutes === 0) return `${hours}小时`
  return `${hours}小时${minutes}分钟`
}

let darkModeSnapshot = false

const selectRecentLlmModel = (model) => {
  form.modelBaseUrl = model.baseUrl
  form.modelName = model.modelName
  form.llmStreamingModelName = model.streamingModelName || model.modelName
}

const selectRecentLlmModelByIndex = () => {
  if (recentModelSelection.value === '') return
  const model = form.recentLlmModels[Number(recentModelSelection.value)]
  if (model) selectRecentLlmModel(model)
}

const applyForm = (settings) => {
  Object.assign(form, { ...DEFAULT_SETTINGS, ...settings })
  recentModelSelection.value = ''
  form.proactiveIdleSeconds = normalizeProactiveIdleSeconds(form.proactiveIdleSeconds)
}

const loadSettings = async () => {
  try {
    const config = await fetchRuntimeConfig()
    applyForm(runtimeConfigToSettings(config))
  } catch (e) {
    console.warn('从后端加载配置失败，使用本地值', e)
    applyForm(props.initialSettings)
  }
  darkModeSnapshot = form.darkMode
  await Promise.all([loadTtsAvatars(), loadSearchHealth(), loadInterests()])
}

onMounted(async () => {
  await loadSettings()
})

watch(() => props.show, (newVal, oldVal) => {
  if (oldVal && !newVal && form.darkMode !== darkModeSnapshot) {
    form.darkMode = darkModeSnapshot
    document.documentElement.classList.toggle('dark', darkModeSnapshot)
  }
  if (newVal) {
    loadSettings()
    darkModeSnapshot = form.darkMode
  }
})

watch(() => form.darkMode, (isDark) => {
  document.documentElement.classList.toggle('dark', isDark)
})

const saveSettings = async () => {
  form.proactiveIdleSeconds = normalizeProactiveIdleSeconds(form.proactiveIdleSeconds)
  form.recentLlmModels = rememberRecentLlmModel(form, form.recentLlmModels)
  saving.value = true
  try {
    const saved = await saveRuntimeConfig({ ...form })
    applyForm(saved)
    darkModeSnapshot = form.darkMode
    emit('save', { ...form })
    emit('close')
    ElMessage.success('配置已保存；MemOS 内部模型将在重启应用后生效')
  } catch (e) {
    console.error(e)
    ElMessage.error('保存配置失败')
  } finally {
    saving.value = false
  }
}

const normalizeTtsAvatars = (avatars) => {
  if (!Array.isArray(avatars)) return []
  const seen = new Set()
  return avatars
    .map((avatar) => ({
      id: String(avatar?.id || '').trim(),
      name: String(avatar?.name || '').trim(),
      description: String(avatar?.description || '').trim(),
      referenceCount: avatar?.referenceCount
    }))
    .filter((avatar) => avatar.id)
    .filter((avatar) => {
      if (seen.has(avatar.id)) return false
      seen.add(avatar.id)
      return true
    })
}

const loadTtsAvatars = async () => {
  const baseUrl = form.astraTtsBaseUrl?.trim()
  ttsAvatarError.value = ''
  if (!baseUrl) {
    ttsAvatars.value = []
    return
  }

  loadingTtsAvatars.value = true
  try {
    const resp = await apiFetch(`/api/runtime-config/tts-avatars?baseUrl=${encodeURIComponent(baseUrl)}`)
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`)
    }
    const data = await resp.json()
    ttsAvatars.value = normalizeTtsAvatars(data)
    if (form.astraDefaultAvatarId && !ttsAvatars.value.some((avatar) => avatar.id === form.astraDefaultAvatarId)) {
      ttsAvatarError.value = `当前选择 ${form.astraDefaultAvatarId} 不在这个 TTS 服务的注册列表中`
    }
  } catch (e) {
    console.error(e)
    ttsAvatars.value = []
    ttsAvatarError.value = '读取 TTS 音色列表失败'
  } finally {
    loadingTtsAvatars.value = false
  }
}

const rebuildRagIndex = async () => {
  rebuildingRag.value = true
  try {
    const resp = await apiFetch('/api/rag/reload', { method: 'POST' })
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`)
    }
    const result = await resp.json()
    ElMessage.success(`RAG 索引已重建，共 ${result.chunkCount ?? 0} 个分块`)
  } catch (e) {
    console.error(e)
    ElMessage.error('RAG 索引重建失败')
  } finally {
    rebuildingRag.value = false
  }
}

const loadSearchHealth = async () => {
  try {
    const resp = await apiFetch('/api/search/health')
    if (resp.ok) searchHealth.value = await resp.json()
  } catch (e) { searchHealth.value = null }
}

const testSearch = async () => {
  if (!searchTestQuery.value.trim()) return
  testingSearch.value = true
  searchTestResult.value = ''
  try {
    const resp = await apiFetch('/api/search/test', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: searchTestQuery.value.trim() })
    })
    const data = await resp.json()
    searchTestResult.value = data.sources?.length
      ? data.sources.map((s, i) => `[${i + 1}] ${s.title}\n${s.url}`).join('\n')
      : (data.contextText || '没有可靠来源')
    await loadSearchHealth()
  } catch (e) { searchTestResult.value = '测试失败' }
  finally { testingSearch.value = false }
}

const loadInterests = async () => {
  if (!props.userId) { interests.value = []; return }
  try {
    const resp = await apiFetch(`/api/proactive-research/interests?userId=${props.userId}`)
    if (resp.ok) interests.value = await resp.json()
  } catch (e) { interests.value = [] }
}

const addInterest = async () => {
  if (!props.userId || !newInterest.value.trim()) return
  await apiFetch('/api/proactive-research/interests', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId: props.userId, topic: newInterest.value.trim() })
  })
  newInterest.value = ''
  await loadInterests()
}

const toggleInterest = async (interest) => {
  await apiFetch(`/api/proactive-research/interests/${interest.id}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...interest, userId: props.userId, enabled: !interest.enabled })
  })
  await loadInterests()
}

const deleteInterest = async (interest) => {
  await apiFetch(`/api/proactive-research/interests/${interest.id}?userId=${props.userId}`, { method: 'DELETE' })
  await loadInterests()
}

const refreshInterests = async () => {
  if (!props.userId) return
  await apiFetch('/api/proactive-research/actions/refresh-interests', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId: props.userId })
  })
  await loadInterests()
}

defineExpose({ loadSettings })
</script>
