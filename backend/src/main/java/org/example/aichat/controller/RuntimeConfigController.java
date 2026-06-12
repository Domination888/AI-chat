package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import org.example.aichat.config.RuntimeConfig;
import org.example.aichat.config.RuntimeConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全链路运行时配置：LLM / Embedding / ASR / TTS / Memos / 客户端偏好。
 * <p>
 * GET  /api/runtime-config          → 当前生效配置
 * PUT  /api/runtime-config          → 合并更新并持久化，立即生效
 * GET  /api/runtime-config/defaults → yml 启动默认值（用于重置参考）
 */
@RestController
@RequestMapping("/api/runtime-config")
@RequiredArgsConstructor
public class RuntimeConfigController {

    private final RuntimeConfigService runtimeConfigService;

    @GetMapping
    public ResponseEntity<RuntimeConfig> getConfig() {
        return ResponseEntity.ok(runtimeConfigService.getEffective());
    }

    @GetMapping("/defaults")
    public ResponseEntity<RuntimeConfig> getDefaults() {
        return ResponseEntity.ok(runtimeConfigService.getYmlDefaults());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody RuntimeConfig patch) {
        RuntimeConfig merged = runtimeConfigService.update(patch);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "updated");
        resp.put("config", merged);
        return ResponseEntity.ok(resp);
    }
}
