package org.example.aichat.config;

import lombok.Data;

import java.util.List;

/**
 * 全链路运行时配置 DTO，对应 config/runtime-config.json。
 * 各 section 字段为 null 表示「不修改 / 使用 yml 或当前值」。
 */
@Data
public class RuntimeConfig {

    private LlmSection llm = new LlmSection();
    private EmbeddingSection embedding = new EmbeddingSection();
    private VoiceSection voice = new VoiceSection();
    private MemosSection memos = new MemosSection();
    private SearchSection search = new SearchSection();
    private ClientSection client = new ClientSection();

    @Data
    public static class LlmSection {
        private String baseUrl;
        private String modelName;
        private String apiKey;
        private String thinkingMode;
        private String reasoningEffort;
        private Boolean utilityInheritConnection;
        private String utilityBaseUrl;
        private String utilityApiKey;
        private String utilityModelName;
        private String utilityThinkingMode;
        private String utilityReasoningEffort;
        private String streamingModelName;
        private Long connectTimeoutMs;
        private Long readTimeoutMs;
        private Integer maxRetries;
    }

    @Data
    public static class EmbeddingSection {
        private String baseUrl;
        private String modelName;
        private String apiKey;
    }

    @Data
    public static class VoiceSection {
        private String asrUrl;
        private String asrLanguage;
        private Integer asrTimeoutMs;
        private String ttsEngine;
        private String astraTtsBaseUrl;
        private String astraDefaultAvatarId;
        private Integer astraStreamingChunkSize;
        private Integer ttsTimeoutMs;
        private String ttsDefaultProfile;
    }

    @Data
    public static class MemosSection {
        private Boolean enabled;
        private String baseUrl;
        private Integer searchTopK;
        private String searchMode;
        private Double relativity;
        private Boolean includePreference;
        private Integer prefTopK;
        private String dedup;
        private Boolean searchToolMemory;
        private Boolean includeSkillMemory;
        private Boolean saveAssistantTurns;
        private Boolean fallbackToRag;
        private Boolean modelInheritConnection;
        private String modelBaseUrl;
        private String modelApiKey;
        private String modelName;
        private Boolean embeddingInheritConnection;
        private String embeddingBaseUrl;
        private String embeddingApiKey;
        private String embeddingModelName;
        private Integer embeddingDimension;
    }

    @Data
    public static class SearchSection {
        private String searxngUrl;
        private Boolean queryPlannerEnabled;
        private Integer plannerTimeoutMs;
        private Integer maxQueries;
        private Integer resultsPerQuery;
        private Integer fetchPages;
        private Integer maxSources;
        private Integer pageTimeoutMs;
        private Integer totalTimeoutMs;
        private Integer resultCacheMinutes;
        private Integer pageCacheHours;
        private String engines;
    }

    @Data
    public static class ClientSection {
        private Double ttsSpeed;
        private Double ttsPitch;
        private Boolean autoPlayTts;
        private Boolean darkMode;
        private Boolean proactiveChatEnabled;
        private Integer proactiveIdleSeconds;
        private String proactivePrompt;
        private Boolean autoResearchEnabled;
        private Integer researchIntervalMinutes;
        private Integer researchDeliveryIdleSeconds;
        private Integer researchCooldownMinutes;
        private String researchQuietStart;
        private String researchQuietEnd;
        private Integer researchScoreThreshold;
        private List<RecentLlmModel> recentLlmModels;
    }

    @Data
    public static class RecentLlmModel {
        private String modelName;
        private String baseUrl;
        private String streamingModelName;
    }
}
