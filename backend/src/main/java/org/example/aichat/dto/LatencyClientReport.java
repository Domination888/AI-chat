package org.example.aichat.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 前端全链路延迟上报（epoch 毫秒时间戳） */
@Data
public class LatencyClientReport {
    private String traceId;
    private String conversationId;
    /** step → epochMs，如 client_fetch_start / client_first_text / client_last_tts_play */
    private Map<String, Long> steps = new LinkedHashMap<>();
}
