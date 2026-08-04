package org.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "memos")
public class MemosProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:8000";
    private String apiKey = "";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 8000;
    private int searchTopK = 10;
    /** add 建议 sync，确保 MemReader 加工完成后再返回 */
    private String asyncMode = "sync";
    private boolean fallbackToRag = true;

    /** MemOS 记忆提取/总结模型是否继承辅助模型连接 */
    private boolean modelInheritConnection = true;
    private String modelBaseUrl = "";
    private String modelApiKey = "";
    private String modelName = "";

    /** MemOS 向量模型是否继承应用的 RAG Embedding 连接 */
    private boolean embeddingInheritConnection = true;
    private String embeddingBaseUrl = "";
    private String embeddingApiKey = "";
    private String embeddingModelName = "";
    private int embeddingDimension = 1024;

    /** 固定的 Memos user_id（UUID 格式），纯个人项目所有对话共享同一用户 */
    private String userId = "";
    /** 默认 writable cube IDs（逗号分隔），用于 /product/add */
    private String writableCubeIds = "";
    /** 默认 readable cube IDs（逗号分隔），用于 /product/search */
    private String readableCubeIds = "";
    /** 搜索模式：fast / fine / mixture */
    private String searchMode = "fast";
    /** 搜索相关性阈值，0 表示不过滤，默认 0.45 */
    private double relativity = 0.45;
    /** 是否在搜索中包含用户偏好记忆 */
    private boolean includePreference = true;
    /** 偏好记忆 top_k */
    private int prefTopK = 6;
    /** Memos 搜索去重策略：no / sim / mmr */
    private String dedup = "mmr";
    /** 是否搜索工具记忆 */
    private boolean searchToolMemory = true;
    /** 工具记忆 top_k */
    private int toolMemTopK = 6;
    /** 是否搜索技能记忆 */
    private boolean includeSkillMemory = true;
    /** 技能记忆 top_k */
    private int skillMemTopK = 3;
    /** 搜索时透传给 MemOS 的最近历史条数 */
    private int searchHistoryMessages = 12;
    /** 写入 MemOS 时是否保存本轮 assistant 回复，使记忆更接近 MemOS chat 的 query+answer 写回 */
    private boolean saveAssistantTurns = true;
    /**
     * 多角色隔离时是否包含 session_id=default_session 的旧记忆。
     * 默认 false：只读当前角色 role_{id} 桶内记忆。
     */
    private boolean includeLegacyMemories = false;

    /**
     * 获取 Memos user_id。
     * 纯个人项目：所有对话共享同一个 userId，不按项目用户 ID 区分。
     */
    public String getEffectiveUserId() {
        if (userId != null && !userId.isBlank()) return userId.trim();
        return "default-user";
    }

    /** 辅助方法：解析逗号分隔的 cube IDs 为 List */
    public List<String> parseWritableCubeIds() {
        return parseCubeIds(writableCubeIds);
    }

    public List<String> parseReadableCubeIds() {
        return parseCubeIds(readableCubeIds);
    }

    private List<String> parseCubeIds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
