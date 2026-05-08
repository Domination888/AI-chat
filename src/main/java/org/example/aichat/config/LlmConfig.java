package org.example.aichat.config;

import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LLM / Embedding 客户端配置
 * 所有连接信息都在 application-local.yml（llm.* / embedding.*）里，严禁硬编码。
 * 规则：LLM 必须走 Win LM Studio（唯一有 NVIDIA GPU 的节点）。
 */
@Configuration
public class LlmConfig {

    @Value("${llm.base-url}")
    private String llmBaseUrl;

    @Value("${llm.model-name}")
    private String llmModelName;

    @Value("${llm.streaming-model-name:${llm.model-name}}")
    private String llmStreamingModelName;

    @Value("${llm.connect-timeout-ms:3000}")
    private long llmConnectTimeoutMs;

    @Value("${llm.read-timeout-ms:60000}")
    private long llmReadTimeoutMs;

    @Value("${llm.max-retries:1}")
    private int llmMaxRetries;

    @Value("${embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${embedding.model-name}")
    private String embeddingModelName;

    @Bean
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(llmBaseUrl)
                .modelName(llmStreamingModelName)
                .timeout(Duration.ofMillis(llmReadTimeoutMs))
                .httpClientBuilder(new SpringRestClientBuilder())
                .build();
    }

    /**
     * 非流式 ChatModel，用于记忆压缩摘要等内部调用。
     */
    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(llmBaseUrl)
                .modelName(llmModelName)
                .timeout(Duration.ofMillis(llmReadTimeoutMs))
                .maxRetries(llmMaxRetries)
                .httpClientBuilder(new SpringRestClientBuilder())
                .build();
    }

    /**
     * EmbeddingModel for RAG 向量检索
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .modelName(embeddingModelName)
                .timeout(Duration.ofMillis(llmReadTimeoutMs))
                .maxRetries(llmMaxRetries)
                .httpClientBuilder(new SpringRestClientBuilder())
                .build();
    }
}