package org.example.aichat.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {
    // 必填
    private String userId;

    // 必填：用于区分多个对话
    private String conversationId;

    // 可为空（纯图片时）
    private String message;

    // 可为空（纯文本时）
    private List<String> images;   // base64字符串数组

    // 是否流式
    private Boolean stream = true;

    // 是否启用联网搜索
    private Boolean search = false;

    // 是否启用本地知识库检索（RAG）
    private Boolean rag = false;
}
