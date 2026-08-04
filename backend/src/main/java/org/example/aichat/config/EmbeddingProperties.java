package org.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Embedding 动态配置：支持热加载（前端随时修改 baseUrl / modelName）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProperties {

    private String baseUrl = "http://127.0.0.1:1234/v1";
    private String modelName = "text-embedding-embeddinggemma-300m";
    /** 外部 OpenAI 兼容 Embedding 服务的 API Key；本地服务可留空 */
    private String apiKey = "";
}
