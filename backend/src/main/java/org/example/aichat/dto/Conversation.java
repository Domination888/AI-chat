package org.example.aichat.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Conversation {
    private String id;
    private Integer userId;
    private Integer roleId;
    private String title;
    private LocalDateTime createdAt;
}