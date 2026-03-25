package org.example.aichat.service;

import org.example.aichat.dto.ChatRequest;
import reactor.core.publisher.Flux;

public interface ChatService {

    /**
     * 流式返回 AI 回答
     */
    Flux<String> chatStream(ChatRequest request);
}