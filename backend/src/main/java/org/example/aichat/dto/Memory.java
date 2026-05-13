package org.example.aichat.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Memory {
    private Long id;
    private String conversationId;
    private String summary;
    private Integer tokenCount;
    private LocalDateTime updatedAt;
}