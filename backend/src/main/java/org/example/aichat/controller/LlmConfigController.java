package org.example.aichat.controller;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.example.aichat.config.LlmProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LLM 动态配置接口：前端可随时查看/修改当前模型地址和名称，无需重启后端。
 * <p>
 * GET  /api/llm-config  → 返回当前生效的 baseUrl + modelName
 * PUT  /api/llm-config  → 覆盖 baseUrl / modelName，立即对所有后续请求生效
 * <p>
 * 支持传入空/null 值来重置为 yml 默认值。
 */
@RestController
@RequestMapping("/api/llm-config")
@RequiredArgsConstructor
public class LlmConfigController {

    private final LlmProperties llmProperties;

    /** yml 初始值（启动时快照，用于重置） */
    private String ymlDefaultBaseUrl;
    private String ymlDefaultModelName;

    @Data
    public static class LlmConfigDTO {
        private String baseUrl;
        private String modelName;
    }

    /**
     * 获取当前生效的 LLM 配置
     */
    @GetMapping
    public ResponseEntity<LlmConfigDTO> getConfig() {
        // 首次调用时记录 yml 默认值
        captureYmlDefaults();

        LlmConfigDTO dto = new LlmConfigDTO();
        dto.setBaseUrl(llmProperties.getBaseUrl());
        dto.setModelName(llmProperties.getModelName());
        return ResponseEntity.ok(dto);
    }

    /**
     * 动态修改 LLM 配置（热加载，立即生效）
     * 传入空/null 则重置为 yml 默认值
     */
    @PutMapping
    public ResponseEntity<Map<String, String>> updateConfig(@RequestBody LlmConfigDTO dto) {
        captureYmlDefaults();

        String baseUrl = (dto.getBaseUrl() != null && !dto.getBaseUrl().isBlank())
                ? dto.getBaseUrl().trim() : ymlDefaultBaseUrl;
        String modelName = (dto.getModelName() != null && !dto.getModelName().isBlank())
                ? dto.getModelName().trim() : ymlDefaultModelName;

        llmProperties.setBaseUrl(baseUrl);
        llmProperties.setModelName(modelName);
        llmProperties.setStreamingModelName(modelName);

        return ResponseEntity.ok(Map.of(
                "baseUrl", llmProperties.getBaseUrl(),
                "modelName", llmProperties.getModelName(),
                "status", "updated"
        ));
    }

    /** 记录 yml 初始值（仅首次调用时记录，后续热更新不再覆盖） */
    private void captureYmlDefaults() {
        if (ymlDefaultBaseUrl == null) {
            ymlDefaultBaseUrl = llmProperties.getBaseUrl();
            ymlDefaultModelName = llmProperties.getModelName();
        }
    }
}