package org.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * LLM 动态配置：支持热加载（前端随时修改 baseUrl / modelName）。
 * <p>
 * 默认值从 application-local.yml 的 llm.* 读取；
 * 运行期间可通过 PUT /api/llm-config 实时覆盖，无需重启后端。
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private static final Set<String> THINKING_MODES = Set.of("auto", "enabled", "disabled");
    private static final Set<String> REASONING_EFFORTS = Set.of("auto", "low", "high", "xhigh", "max");

    /** LLM API 地址（OpenAI 兼容），默认从 yml 读取 */
    private String baseUrl;

    /** 模型名称 */
    private String modelName;

    /** 外部 OpenAI 兼容服务的 API Key；本地无鉴权服务可留空 */
    private String apiKey = "";

    /** 思考模式：auto 跟随服务默认值，或 enabled / disabled */
    private String thinkingMode = "auto";

    /** 思考强度：auto 跟随服务默认值，或 low / high / xhigh / max */
    private String reasoningEffort = "auto";

    /** 内部总结、分类、查询规划等轻量任务是否复用主模型的连接信息 */
    private boolean utilityInheritConnection = true;

    /** 辅助模型独立 Base URL；utilityInheritConnection=false 时生效 */
    private String utilityBaseUrl = "";

    /** 辅助模型独立 API Key；本地模型可留空 */
    private String utilityApiKey = "";

    /** 辅助模型名称；留空时复用主 modelName */
    private String utilityModelName = "";

    /** 辅助模型默认关闭思考，降低分类、总结和查询规划的延迟 */
    private String utilityThinkingMode = "disabled";

    /** 辅助模型思考强度 */
    private String utilityReasoningEffort = "auto";

    /** 流式模型名称（默认同 modelName） */
    private String streamingModelName;

    /** 连接超时（ms），默认 3000 */
    private long connectTimeoutMs = 3000;

    /** 读超时（ms），默认 60000 */
    private long readTimeoutMs = 60000;

    /** 重试次数，默认 1 */
    private int maxRetries = 1;

    /**
     * 获取生效的流式模型名称：streamingModelName 非空则用它，否则 fallback 到 modelName。
     */
    public String getEffectiveStreamingModelName() {
        return (streamingModelName != null && !streamingModelName.isBlank())
                ? streamingModelName : modelName;
    }

    public String getEffectiveThinkingMode() {
        String normalized = normalize(thinkingMode);
        return THINKING_MODES.contains(normalized) ? normalized : "auto";
    }

    public String getEffectiveReasoningEffort() {
        String normalized = normalize(reasoningEffort);
        return REASONING_EFFORTS.contains(normalized) ? normalized : "auto";
    }

    public String getEffectiveUtilityBaseUrl() {
        return utilityInheritConnection || !hasText(utilityBaseUrl) ? baseUrl : utilityBaseUrl.trim();
    }

    public String getEffectiveUtilityApiKey() {
        return utilityInheritConnection ? apiKey : utilityApiKey;
    }

    public String getEffectiveUtilityModelName() {
        return hasText(utilityModelName) ? utilityModelName.trim() : modelName;
    }

    public String getEffectiveUtilityThinkingMode() {
        String normalized = normalize(utilityThinkingMode);
        return THINKING_MODES.contains(normalized) ? normalized : "disabled";
    }

    public String getEffectiveUtilityReasoningEffort() {
        String normalized = normalize(utilityReasoningEffort);
        return REASONING_EFFORTS.contains(normalized) ? normalized : "auto";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
