package org.example.aichat.config;

import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 可热更新的 EmbeddingModel 持有者。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingModelHolder {

    private final EmbeddingProperties embeddingProperties;
    private final LlmProperties llmProperties;

    private volatile EmbeddingModel model;

    @PostConstruct
    public void init() {
        refresh();
    }

    public EmbeddingModel get() {
        return model;
    }

    public synchronized void refresh() {
        var builder = OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingProperties.getBaseUrl())
                .modelName(embeddingProperties.getModelName())
                .timeout(Duration.ofMillis(llmProperties.getReadTimeoutMs()))
                .maxRetries(llmProperties.getMaxRetries())
                .httpClientBuilder(new SpringRestClientBuilder());
        if (embeddingProperties.getApiKey() != null && !embeddingProperties.getApiKey().isBlank()) {
            builder.apiKey(embeddingProperties.getApiKey().trim());
        }
        model = builder.build();
        log.info("EmbeddingModel 已刷新: baseUrl={}, modelName={}",
                embeddingProperties.getBaseUrl(), embeddingProperties.getModelName());
    }
}
