package org.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "memos")
public class MemosProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:8000";
    private String apiKey = "";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 8000;
    private int searchTopK = 10;
    private String asyncMode = "async";
    private boolean roleFilterEnabled = false;
    private boolean fallbackToRag = true;

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