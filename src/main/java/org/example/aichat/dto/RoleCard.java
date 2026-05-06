package org.example.aichat.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RoleCard {
    private Integer id;
    private String name;
    private String avatar;
    private String profile;
    private String background;
    private String personality;
    private String exampleDialogue;
    private String greeting;
    private String voiceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
