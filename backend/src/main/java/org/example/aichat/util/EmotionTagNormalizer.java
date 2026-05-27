package org.example.aichat.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 情绪标签规范化工具（全局唯一真相源）。
 * <p>
 * 合法标签集合与前端 emotion-mappings.js 对齐：
 * <开心> <生气> <难过> <惊讶> <害羞> <俏皮>
 * <p>
 * 用途：
 * 1. ChatServiceImpl 保存 AI 回复到 ChatMemory 前规范化
 * 2. MysqlChatMemoryStore 加载历史消息时规范化（修复已入库的越界标签）
 * 3. EmotionTagBuffer 剥离所有 <xxx> 格式标签（含越界标签，防止漏给前端）
 */
public final class EmotionTagNormalizer {

    private EmotionTagNormalizer() {}

    /** 合法情绪标签集合 */
    public static final Set<String> VALID_EMOTION_TAGS = Set.of(
            "开心", "生气", "难过", "惊讶", "害羞", "俏皮"
    );

    /** 匹配所有 <xxx> 格式标签（xxx 为非 > 字符序列） */
    public static final Pattern ANY_TAG_PATTERN = Pattern.compile("<([^>]+)>");

    /** 仅匹配合法情绪标签的正则（用于 EmotionTagBuffer 精确提取） */
    public static final Pattern VALID_TAG_PATTERN = Pattern.compile(
            "<(开心|生气|难过|惊讶|害羞|俏皮)>"
    );

    /** 兜底默认情绪 */
    public static final String DEFAULT_EMOTION = "开心";

    /**
     * 规范化情绪标签：将不在规定范围的 <xxx> 标签替换为最近出现的合法标签，
     * 如果前面没有合法标签则替换为 <开心>。保留合法标签不变。
     * <p>
     * 这样做是为了防止 LLM 从历史消息中学到非规定标签（如 <温和>），持续输出越界标签。
     *
     * @param text 原始文本
     * @return 规范化后的文本
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = ANY_TAG_PATTERN.matcher(text);
        String lastValid = DEFAULT_EMOTION;
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tagContent = m.group(1);
            if (VALID_EMOTION_TAGS.contains(tagContent)) {
                lastValid = tagContent;
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement("<" + lastValid + ">"));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 剥离所有 <xxx> 格式标签（含越界标签），返回纯文本。
     * 用于 EmotionTagBuffer：确保越界标签不会漏给前端用户看到。
     *
     * @param text 原始文本
     * @return 剥离所有标签后的纯文本
     */
    public static String stripAllTags(String text) {
        if (text == null || text.isEmpty()) return text;
        return ANY_TAG_PATTERN.matcher(text).replaceAll("");
    }

    /**
     * 判断标签内容是否合法
     */
    public static boolean isValidTag(String tagContent) {
        return VALID_EMOTION_TAGS.contains(tagContent);
    }
}