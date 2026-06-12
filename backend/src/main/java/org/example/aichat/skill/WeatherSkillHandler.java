package org.example.aichat.skill;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.example.aichat.mcp.McpClientManager;
import org.example.aichat.util.WebSearchHelper;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * weather-lookup 技能：匹配天气意图、解析城市、拉取 wttr.in 实况，必要时辅以 SearXNG。
 */
public final class WeatherSkillHandler {

    public static final String SKILL_NAME = "weather-lookup";

    private static final Pattern WEATHER_INTENT = Pattern.compile(
            "天气|气温|温度|几度|多少度|冷不冷|热不热|下雨|下雪|刮风|预报|穿衣");
    private static final Pattern CITY = WebSearchHelper.cityPattern();
    private static final Pattern VAGUE_ONLY = Pattern.compile(
            "^(今天|明天)?\\s*(天气|气温|温度|几度|多少度|具体|到底|情况|如何|怎样)\\s*$");

    private WeatherSkillHandler() {
    }

    public static boolean isEnabled(List<SkillManifest> skills) {
        return skills.stream().anyMatch(s -> SKILL_NAME.equalsIgnoreCase(s.getName()) && s.isEnabled());
    }

    public static boolean matches(String message, List<ChatMessage> history) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (WEATHER_INTENT.matcher(message).find()) {
            return true;
        }
        if (VAGUE_ONLY.matcher(message.trim()).matches() && resolveCity(message, history) != null) {
            return true;
        }
        return false;
    }

    public static String resolveCity(String message, List<ChatMessage> history) {
        if (message != null) {
            Matcher m = CITY.matcher(message);
            if (m.find()) {
                return m.group(1);
            }
        }
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatMessage msg = history.get(i);
                if (!(msg instanceof UserMessage userMsg)) {
                    continue;
                }
                String text = extractText(userMsg);
                if (text == null || text.isBlank()) {
                    continue;
                }
                Matcher m = CITY.matcher(text);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    /**
     * 组装注入块；无城市或拉取失败时返回 null（由模型按技能说明追问或自行调工具）。
     */
    public static String buildContext(String message, List<ChatMessage> history,
                                      boolean supplementWebSearch, McpClientManager mcp) {
        String city = resolveCity(message, history);
        if (city == null) {
            return null;
        }

        String synthetic = city + " 天气 今天";
        StringBuilder sb = new StringBuilder();
        String snapshot = WebSearchHelper.weatherSnapshotForCity(city);
        if (snapshot != null) {
            sb.append(snapshot);
        }

        if (supplementWebSearch && mcp != null) {
            String searx = mcp.webSearch(synthetic);
            if (searx != null && !searx.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n\n【补充检索】\n");
                }
                sb.append(searx.trim());
            }
        }

        if (sb.length() == 0) {
            return null;
        }
        return sb.toString().trim();
    }

    public static String injectionHint() {
        return "请根据以上天气数据直接回答用户问题。必须如实写出气温（°C）与天气现象，不要编造，"
                + "不要只回复「好的，我已了解」；角色口吻不能替代具体数据。"
                + "若缺少城市或数据，按技能说明追问或调用 webSearch（query 格式：{城市} 天气 今天）。";
    }

    private static String extractText(UserMessage userMsg) {
        if (userMsg.contents() == null || userMsg.contents().isEmpty()) {
            return null;
        }
        var first = userMsg.contents().get(0);
        if (first instanceof dev.langchain4j.data.message.TextContent tc) {
            return tc.text();
        }
        return null;
    }
}
