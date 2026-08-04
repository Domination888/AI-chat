package org.example.aichat.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProactiveCandidate {
    private Long id;
    private Integer userId;
    private String conversationId;
    private String topic;
    private String title;
    private String summary;
    private String reason;
    private String sourcesJson;
    private Double score;
    private String fingerprint;
    private String status;
    private String responseText;
    private String feedback;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime deliveredAt;
}
