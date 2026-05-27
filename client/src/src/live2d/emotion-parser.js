/**
 * 情绪标签解析器
 *
 * 从文本中提取 <开心> <生气> 等中文情绪标签，
 * 并剥离标签返回纯文本，同时记录每个标签在纯文本中的位置和对应映射。
 */

import { EMOTION_TAGS, getEmotionMapping } from './emotion-mappings.js'

// 正则：匹配 <xxx> 格式的标签（xxx 为非 > 字符序列）
const EMOTION_TAG_REGEX = /<([^>]+)>/g

/**
 * 从文本中提取所有情绪标签
 * @param {string} text 原始文本（可能包含 <开心> <生气> 等标签）
 * @returns {Array<{emotion: string, position: number, fullTag: string}>}
 *   position 是标签在原文中的起始位置
 */
export function parseEmotionTags(text) {
  if (!text) return []
  const results = []
  let match
  EMOTION_TAG_REGEX.lastIndex = 0
  while ((match = EMOTION_TAG_REGEX.exec(text)) !== null) {
    const tagContent = match[1]
    if (EMOTION_TAGS.has(tagContent)) {
      results.push({
        emotion: tagContent,
        position: match.index,
        fullTag: match[0],
      })
    }
  }
  return results
}

/**
 * 从文本中移除所有 <xxx> 情绪标签，返回纯文本
 * @param {string} text 原始文本
 * @returns {string} 纯文本（不含情绪标签）
 */
export function stripEmotionTags(text) {
  if (!text) return ''
  return text.replace(EMOTION_TAG_REGEX, (fullMatch, tagContent) => {
    // 只剥离已知情绪标签，保留其他 <xxx> 格式的内容（如 HTML 标签等）
    return EMOTION_TAGS.has(tagContent) ? '' : fullMatch
  })
}

/**
 * 剥离标签 + 记录每个标签在纯文本中的位置和对应映射
 * 这是最常用的函数：前端收到 LLM 流式文本后，用这个函数处理
 *
 * @param {string} text 原始文本
 * @returns {{
 *   cleanText: string,
 *   markers: Array<{
 *     emotion: string,
 *     positionInCleanText: number,
 *     mapping: object|null
 *   }>
 * }}
 */
export function prepareTextWithMarkers(text) {
  if (!text) return { cleanText: '', markers: [] }

  // 先找出所有标签及其在原文中的位置
  const tags = parseEmotionTags(text)

  // 计算每个标签在纯文本中的位置
  // 因为剥离标签后纯文本会缩短，需要累计偏移量
  let offsetAdjust = 0
  const markers = []
  for (const tag of tags) {
    const positionInCleanText = tag.position - offsetAdjust
    const mapping = getEmotionMapping(tag.emotion)
    markers.push({
      emotion: tag.emotion,
      positionInCleanText,
      mapping,
    })
    offsetAdjust += tag.fullTag.length
  }

  const cleanText = stripEmotionTags(text)

  return { cleanText, markers }
}