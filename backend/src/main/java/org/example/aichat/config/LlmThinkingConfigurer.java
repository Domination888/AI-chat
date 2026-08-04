package org.example.aichat.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将 DeepSeek/OpenAI 兼容的思考参数应用到 LangChain4j 模型。
 * returnThinking + sendThinking 用于保留并回传工具调用子轮的 reasoning_content。
 */
public final class LlmThinkingConfigurer {

    private static final Set<String> MODES = Set.of("auto", "enabled", "disabled");
    private static final Set<String> EFFORTS = Set.of("auto", "low", "high", "xhigh", "max");

    private LlmThinkingConfigurer() {
    }

    public static void configure(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
                                 String thinkingMode,
                                 String reasoningEffort) {
        String mode = normalize(thinkingMode, MODES);
        if (!"auto".equals(mode)) {
            builder.customParameters(Map.of("thinking", Map.of("type", mode)));
        }
        if (!"disabled".equals(mode)) {
            builder.returnThinking(true).sendThinking(true, "reasoning_content");
            String effort = normalize(reasoningEffort, EFFORTS);
            if (!"auto".equals(effort)) builder.reasoningEffort(effort);
        }
    }

    public static void configure(OpenAiChatModel.OpenAiChatModelBuilder builder,
                                 String thinkingMode,
                                 String reasoningEffort) {
        String mode = normalize(thinkingMode, MODES);
        if (!"auto".equals(mode)) {
            builder.customParameters(Map.of("thinking", Map.of("type", mode)));
        }
        if (!"disabled".equals(mode)) {
            builder.returnThinking(true).sendThinking(true, "reasoning_content");
            String effort = normalize(reasoningEffort, EFFORTS);
            if (!"auto".equals(effort)) builder.reasoningEffort(effort);
        }
    }

    private static String normalize(String value, Set<String> allowed) {
        String normalized = value == null ? "auto" : value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "auto";
    }
}
