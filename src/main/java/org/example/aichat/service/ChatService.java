package org.example.aichat.service;

import org.example.aichat.dto.ChatRequest;
import reactor.core.publisher.Flux;

public interface ChatService {

    /**
     * 流式返回 AI 回答
     */
    Flux<String> chatStream(ChatRequest request);

    /**
     * 阻塞式对话：用于语音接口，直接拿到完整的文本回复
     */
    String chatBlocking(String conversationId, String message, Integer userId, Integer roleId);
}