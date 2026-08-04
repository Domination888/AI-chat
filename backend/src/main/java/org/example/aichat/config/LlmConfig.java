package org.example.aichat.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM / Embedding 客户端配置
 * Bean 级别使用 yml 默认值初始化；运行时动态配置由 LlmProperties 管理。
 * 规则：LLM 必须走 Win LM Studio（唯一有 NVIDIA GPU 的节点）。
 */
@Configuration
public class LlmConfig {

    @Bean
    public StreamingChatModel streamingChatModel(LlmModelFactory factory) {
        return factory.createStreamingChatModel(null, null);
    }

    /**
     * 非流式 ChatModel，用于记忆压缩摘要等内部调用。
     */
    @Bean
    public ChatModel chatModel(LlmModelFactory factory) {
        return factory.createChatModel();
    }

}
