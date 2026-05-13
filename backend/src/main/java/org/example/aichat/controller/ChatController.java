package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import org.example.aichat.dto.ChatRequest;
import org.example.aichat.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    private static final int MAX_IMAGE_COUNT = 5;
    private static final int MAX_BASE64_LENGTH = 5 * 1024 * 1024; // 约 5MB

    /**
     * 流式聊天入口，直接返回 Flux<String>
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request) {
        validateRequest(request);

        return chatService.chatStream(request)
                .map(token -> ServerSentEvent.<String>builder()
                        .data(token)
                        .build());
    }

    /**
     * 参数校验
     */
    private void validateRequest(ChatRequest request) {

        if (request.getUserId() == null) {
            throw new IllegalArgumentException("userId不能为空");
        }

        if (!StringUtils.hasText(request.getConversationId())) {
            throw new IllegalArgumentException("conversationId不能为空");
        }

        boolean hasText = StringUtils.hasText(request.getMessage());
        boolean hasImages = !CollectionUtils.isEmpty(request.getImages());

        if (!hasText && !hasImages) {
            throw new IllegalArgumentException("必须提供文本或图片");
        }

        if (hasImages) {
            validateImages(request.getImages());
        }
    }

    /**
     * 图片校验
     */
    private void validateImages(List<String> images) {

        if (images.size() > MAX_IMAGE_COUNT) {
            throw new IllegalArgumentException("最多支持 " + MAX_IMAGE_COUNT + " 张图片");
        }

        for (String base64 : images) {

            if (!StringUtils.hasText(base64)) {
                throw new IllegalArgumentException("图片内容不能为空");
            }

            // 限制base64长度（防止超大图片）
            if (base64.length() > MAX_BASE64_LENGTH) {
                throw new IllegalArgumentException("单张图片不能超过5MB");
            }

            // 简单校验格式
            if (!base64.startsWith("data:image")) {
                throw new IllegalArgumentException("图片必须为base64格式");
            }
        }
    }
}