/**
 * 情绪 → Live2D 表情/动作映射表
 *
 * 黍模型可用资源：
 *   Expressions（4个）：闭眼、皱眉、闭一只眼、震惊
 *   Motions（Tap#4 组，9个）：种到土里、种到地里、谷种入田野、钓鱼、掐腰、有大麟、生气、晃手、闭眼
 *
 * 情绪标签（6种，对齐 my-neuro）：开心、生气、难过、惊讶、害羞、俏皮
 */

export const EMOTION_MAPPINGS = {
  开心: {
    expression: null,       // 默认表情（自然微笑）
    motion: { group: 'Tap#4', index: 7 },  // 晃手
  },
  生气: {
    expression: '皱眉',
    motion: { group: 'Tap#4', index: 6 },  // 生气
  },
  难过: {
    expression: '闭眼',
    motion: { group: 'Tap#4', index: 4 },  // 掐腰（待机）
  },
  惊讶: {
    expression: '震惊',
    motion: { group: 'Tap#4', index: 5 },  // 有大麟
  },
  害羞: {
    expression: '闭一只眼',
    motion: { group: 'Tap#4', index: 3 },  // 钓鱼
  },
  俏皮: {
    expression: '闭一只眼',
    motion: { group: 'Tap#4', index: 2 },  // 谷种入田野
  },
}

/**
 * 所有支持的情绪标签名称集合
 */
export const EMOTION_TAGS = new Set(Object.keys(EMOTION_MAPPINGS))

/**
 * 获取情绪对应的映射配置
 * @param {string} emotion 情绪名称
 * @returns {object|null} 映射配置 { expression, motion }
 */
export function getEmotionMapping(emotion) {
  return EMOTION_MAPPINGS[emotion] || null
}