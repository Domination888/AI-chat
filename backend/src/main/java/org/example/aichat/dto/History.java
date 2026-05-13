package org.example.aichat.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class History {
    private Long id;
    private String conversationId;
    private String sender;
    private String content;
    private Integer tokenCount;
    private LocalDateTime createdAt;
}