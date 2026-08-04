package org.example.aichat.config;

import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 使用当前运行时配置创建模型，确保设置页热更新同时覆盖流式与内部非流式调用。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmModelFactory {

    private final LlmProperties properties;

    public StreamingChatModel createStreamingChatModel(String requestBaseUrl, String requestModelName) {
        String baseUrl = hasText(requestBaseUrl) ? requestBaseUrl.trim() : properties.getBaseUrl();
        String modelName = hasText(requestModelName) ? requestModelName.trim() : properties.getEffectiveStreamingModelName();
        log.info("创建 StreamingChatModel: baseUrl={}, modelName={}", baseUrl, modelName);
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .httpClientBuilder(new SpringRestClientBuilder());
        applySharedOptions(builder);
        return builder.build();
    }

    public ChatModel createChatModel() {
        return createChatModel(properties.getBaseUrl(), properties.getModelName(), properties.getApiKey(),
                properties.getEffectiveThinkingMode(), properties.getEffectiveReasoningEffort(),
                properties.getReadTimeoutMs());
    }

    public ChatModel createUtilityChatModel() {
        return createUtilityChatModel(properties.getReadTimeoutMs());
    }

    public ChatModel createUtilityChatModel(long timeoutMs) {
        return createChatModel(properties.getEffectiveUtilityBaseUrl(), properties.getEffectiveUtilityModelName(),
                properties.getEffectiveUtilityApiKey(), properties.getEffectiveUtilityThinkingMode(),
                properties.getEffectiveUtilityReasoningEffort(), timeoutMs);
    }

    private ChatModel createChatModel(String baseUrl, String modelName, String apiKey,
                                      String thinkingMode, String reasoningEffort, long timeoutMs) {
        log.info("创建内部 ChatModel: baseUrl={}, modelName={}", baseUrl, modelName);
        var builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofMillis(timeoutMs))
                .maxRetries(properties.getMaxRetries())
                .httpClientBuilder(new SpringRestClientBuilder());
        if (hasText(apiKey)) builder.apiKey(apiKey.trim());
        LlmThinkingConfigurer.configure(builder, thinkingMode, reasoningEffort);
        return builder.build();
    }

    private void applySharedOptions(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder) {
        if (hasText(properties.getApiKey())) builder.apiKey(properties.getApiKey().trim());
        LlmThinkingConfigurer.configure(builder,
                properties.getEffectiveThinkingMode(), properties.getEffectiveReasoningEffort());
    }

    private void applySharedOptions(OpenAiChatModel.OpenAiChatModelBuilder builder) {
        if (hasText(properties.getApiKey())) builder.apiKey(properties.getApiKey().trim());
        LlmThinkingConfigurer.configure(builder,
                properties.getEffectiveThinkingMode(), properties.getEffectiveReasoningEffort());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
