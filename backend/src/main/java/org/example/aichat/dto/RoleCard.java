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
    private String roleCode;
    private String personaCardPath;
    /** Memos 记忆桶 cube_id；空则使用 application 全局 writable/readable-cube-ids */
    private String memosCubeId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
