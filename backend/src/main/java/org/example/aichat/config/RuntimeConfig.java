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
    private ClientSection client = new ClientSection();

    @Data
    public static class LlmSection {
        private String baseUrl;
        private String modelName;
        private String streamingModelName;
        private Long connectTimeoutMs;
        private Long readTimeoutMs;
        private Integer maxRetries;
    }

    @Data
    public static class EmbeddingSection {
        private String baseUrl;
        private String modelName;
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
        private List<RecentLlmModel> recentLlmModels;
    }

    @Data
    public static class RecentLlmModel {
        private String modelName;
        private String baseUrl;
        private String streamingModelName;
    }
}
