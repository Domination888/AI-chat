package org.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** LLM API 地址（OpenAI 兼容），默认从 yml 读取 */
    private String baseUrl = "http://192.168.124.2:1234/v1";

    /** 模型名称 */
    private String modelName = "gemma-4-31b-it";

    /** 流式模型名称（默认同 modelName） */
    private String streamingModelName = "gemma-4-31b-it";

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
}