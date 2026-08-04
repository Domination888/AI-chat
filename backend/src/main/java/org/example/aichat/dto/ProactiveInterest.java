package org.example.aichat.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProactiveInterest {
    private Long id;
    private Integer userId;
    private String topic;
    private String source;
    private Double weight;
    private Boolean enabled;
    private LocalDateTime mutedUntil;
    private String evidence;
    private LocalDateTime lastInferredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
