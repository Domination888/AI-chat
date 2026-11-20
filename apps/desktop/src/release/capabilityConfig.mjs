export function parseConfigJson(text, label, kind = 'object') {
  let value
  try { value = JSON.parse(text) } catch { throw new Error(`${label}格式不正确：请填写有效的 JSON ${kind === 'array' ? '数组（使用 []）' : '对象（使用 {}）'}，使用英文双引号，不要添加注释或末尾逗号。`) }
  if (kind === 'array' ? !Array.isArray(value) : !value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}必须是有效的 JSON ${kind === 'array' ? '数组（使用 []）' : '对象（使用 {}）'}。`)
  return value
}
export function parseCommand(text) {
  const value = parseConfigJson(text, '启动命令', 'array')
  if (!value.length || value.some(item => typeof item !== 'string' || !item.trim())) throw new Error('启动命令必须是非空字符串数组，例如 ["python3", "/path/to/tool.py"]。')
  return value
}
export function parseHeaders(text) {
  const value = parseConfigJson(text, '请求头')
  if (Object.values(value).some(item => typeof item !== 'string')) throw new Error('请求头的每个值都必须是字符串，例如 {"Authorization":"Bearer …"}。')
  return value
}
export const advancedExample = JSON.stringify({ schedule: { enabled: false, hour: 10, minute: 0, zone: 'Asia/Shanghai' }, proactive: { enabled: false, promptTemplate: '结合这项技能的指令，开启一个相关话题。' }, mcpTools: [] }, null, 2)
export function parseAdvanced(text) {
  const value = parseConfigJson(text, '高级配置')
  const allowed = ['schedule', 'source', 'proactive', 'mcpTools']
  if (Object.keys(value).some(key => !allowed.includes(key))) throw new Error('高级配置只支持 schedule、source、proactive、mcpTools；名称、关键词和指令请在对应字段填写。')
  for (const key of ['schedule', 'source', 'proactive']) if (key in value && (!value[key] || typeof value[key] !== 'object' || Array.isArray(value[key]))) throw new Error(`高级配置中的 ${key} 必须是 JSON 对象（使用 {}）。`)
  if ('mcpTools' in value && (!Array.isArray(value.mcpTools) || value.mcpTools.some(name => typeof name !== 'string' || !name.trim()))) throw new Error('高级配置中的 mcpTools 必须是工具名字符串数组，例如 ["echo"]；不限制工具时填写 []。')
  return value
}
