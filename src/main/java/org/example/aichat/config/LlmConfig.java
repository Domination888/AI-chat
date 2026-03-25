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

@Configuration
public class LlmConfig {

    private static final String BASE_URL = "http://localhost:1234/v1";
    private static final String MODEL_NAME = "qwen3.5-9b-ud";

    @Value("${embedding.base-url:http://localhost:1234/v1}")
    private String embeddingBaseUrl;

    @Value("${embedding.model-name:text-embedding-qwen3-embedding-0.6b}")
    private String embeddingModelName;

    @Bean
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(BASE_URL)
                .modelName(MODEL_NAME)
                .httpClientBuilder(new SpringRestClientBuilder())
                .build();
    }

    /**
     * 非流式 ChatModel，用于记忆压缩摘要等内部调用
     */
    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(BASE_URL)
                .modelName(MODEL_NAME)
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
                .httpClientBuilder(new SpringRestClientBuilder())
                .build();
    }
}