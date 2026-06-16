import { apiFetch } from './api.js'

/** 客户端偏好默认值（与 SettingsModal / App.vue 对齐） */
export const DEFAULT_CLIENT = {
  ttsSpeed: 1.0,
  ttsPitch: 1.0,
  autoPlayTts: true,
  darkMode: false,
  proactiveChatEnabled: true,
  proactiveIdleSeconds: 3600,
  proactivePrompt: '[System: 用户长时间未说话，请根据上下文主动搭话，自然地延续对话]'
}

/** 扁平 settings（App.vue 使用）默认值 */
export const DEFAULT_SETTINGS = {
  modelBaseUrl: '',
  modelName: '',
  llmStreamingModelName: '',
  llmReadTimeoutMs: 60000,
  embeddingBaseUrl: '',
  embeddingModelName: '',
  asrUrl: '',
  asrLanguage: 'auto',
  asrTimeoutMs: 15000,
  ttsEngine: 'astra',
  astraTtsBaseUrl: '',
  astraDefaultAvatarId: '',
  astraStreamingChunkSize: 2048,
  ttsTimeoutMs: 60000,
  ttsDefaultProfile: 'shu',
  memosEnabled: true,
  memosBaseUrl: '',
  memosSearchTopK: 10,
  memosSearchMode: 'mixture',
  ...DEFAULT_CLIENT
}

/** GET /api/runtime-config → 扁平 settings */
export function runtimeConfigToSettings(config) {
  const llm = config?.llm || {}
  const embedding = config?.embedding || {}
  const voice = config?.voice || {}
  const memos = config?.memos || {}
  const client = { ...DEFAULT_CLIENT, ...(config?.client || {}) }

  return {
    modelBaseUrl: llm.baseUrl || '',
    modelName: llm.modelName || '',
    llmStreamingModelName: llm.streamingModelName || llm.modelName || '',
    llmReadTimeoutMs: llm.readTimeoutMs ?? 60000,
    embeddingBaseUrl: embedding.baseUrl || '',
    embeddingModelName: embedding.modelName || '',
    asrUrl: voice.asrUrl || '',
    asrLanguage: voice.asrLanguage || 'auto',
    asrTimeoutMs: voice.asrTimeoutMs ?? 15000,
    ttsEngine: voice.ttsEngine || 'astra',
    astraTtsBaseUrl: voice.astraTtsBaseUrl || '',
    astraDefaultAvatarId: voice.astraDefaultAvatarId || '',
    astraStreamingChunkSize: voice.astraStreamingChunkSize ?? 2048,
    ttsTimeoutMs: voice.ttsTimeoutMs ?? 60000,
    ttsDefaultProfile: voice.ttsDefaultProfile || 'shu',
    memosEnabled: memos.enabled ?? true,
    memosBaseUrl: memos.baseUrl || '',
    memosSearchTopK: memos.searchTopK ?? 10,
    memosSearchMode: memos.searchMode || 'mixture',
    ttsSpeed: client.ttsSpeed ?? 1.0,
    ttsPitch: client.ttsPitch ?? 1.0,
    autoPlayTts: client.autoPlayTts !== false,
    darkMode: client.darkMode === true,
    proactiveChatEnabled: client.proactiveChatEnabled !== false,
    proactiveIdleSeconds: client.proactiveIdleSeconds ?? 3600,
    proactivePrompt: client.proactivePrompt || DEFAULT_CLIENT.proactivePrompt
  }
}

/** 扁平 settings → PUT /api/runtime-config */
export function settingsToRuntimeConfig(settings) {
  return {
    llm: {
      baseUrl: settings.modelBaseUrl?.trim() || null,
      modelName: settings.modelName?.trim() || null,
      streamingModelName: settings.llmStreamingModelName?.trim() || settings.modelName?.trim() || null,
      readTimeoutMs: settings.llmReadTimeoutMs ?? null
    },
    embedding: {
      baseUrl: settings.embeddingBaseUrl?.trim() || null,
      modelName: settings.embeddingModelName?.trim() || null
    },
    voice: {
      asrUrl: settings.asrUrl?.trim() || null,
      asrLanguage: settings.asrLanguage || null,
      asrTimeoutMs: settings.asrTimeoutMs ?? null,
      ttsEngine: settings.ttsEngine || null,
      astraTtsBaseUrl: settings.astraTtsBaseUrl?.trim() || null,
      astraDefaultAvatarId: settings.astraDefaultAvatarId?.trim() || null,
      astraStreamingChunkSize: settings.astraStreamingChunkSize ?? null,
      ttsTimeoutMs: settings.ttsTimeoutMs ?? null,
      ttsDefaultProfile: settings.ttsDefaultProfile?.trim() || null
    },
    memos: {
      enabled: settings.memosEnabled ?? null,
      baseUrl: settings.memosBaseUrl?.trim() || null,
      searchTopK: settings.memosSearchTopK ?? null,
      searchMode: settings.memosSearchMode || null
    },
    client: {
      ttsSpeed: settings.ttsSpeed ?? null,
      ttsPitch: settings.ttsPitch ?? null,
      autoPlayTts: settings.autoPlayTts ?? null,
      darkMode: settings.darkMode ?? null,
      proactiveChatEnabled: settings.proactiveChatEnabled ?? null,
      proactiveIdleSeconds: settings.proactiveIdleSeconds ?? null,
      proactivePrompt: settings.proactivePrompt || null
    }
  }
}

export async function fetchRuntimeConfig() {
  const res = await apiFetch('/api/runtime-config')
  if (!res.ok) throw new Error(`加载配置失败: ${res.status}`)
  return res.json()
}

export async function saveRuntimeConfig(settings) {
  const res = await apiFetch('/api/runtime-config', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settingsToRuntimeConfig(settings))
  })
  if (!res.ok) throw new Error(`保存配置失败: ${res.status}`)
  const data = await res.json()
  return data.config ? runtimeConfigToSettings(data.config) : settings
}
