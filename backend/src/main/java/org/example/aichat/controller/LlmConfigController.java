package org.example.aichat.controller;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.example.aichat.config.RuntimeConfig;
import org.example.aichat.config.RuntimeConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LLM 动态配置接口（兼容旧前端路径）。
 * 实际逻辑委托给 {@link RuntimeConfigService}。
 */
@RestController
@RequestMapping("/api/llm-config")
@RequiredArgsConstructor
public class LlmConfigController {

    private final RuntimeConfigService runtimeConfigService;

    @Data
    public static class LlmConfigDTO {
        private String baseUrl;
        private String modelName;
    }

    @GetMapping
    public ResponseEntity<LlmConfigDTO> getConfig() {
        RuntimeConfig.LlmSection llm = runtimeConfigService.getEffective().getLlm();
        LlmConfigDTO dto = new LlmConfigDTO();
        dto.setBaseUrl(llm.getBaseUrl());
        dto.setModelName(llm.getModelName());
        return ResponseEntity.ok(dto);
    }

    @PutMapping
    public ResponseEntity<Map<String, String>> updateConfig(@RequestBody LlmConfigDTO dto) {
        RuntimeConfig patch = new RuntimeConfig();
        RuntimeConfig.LlmSection llm = new RuntimeConfig.LlmSection();
        llm.setBaseUrl(dto.getBaseUrl());
        llm.setModelName(dto.getModelName());
        if (dto.getModelName() != null && !dto.getModelName().isBlank()) {
            llm.setStreamingModelName(dto.getModelName());
        }
        patch.setLlm(llm);
        RuntimeConfig merged = runtimeConfigService.update(patch);
        return ResponseEntity.ok(Map.of(
                "baseUrl", merged.getLlm().getBaseUrl(),
                "modelName", merged.getLlm().getModelName(),
                "status", "updated"
        ));
    }
}
