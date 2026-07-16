package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.aichat.config.RuntimeConfig;
import org.example.aichat.config.RuntimeConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全链路运行时配置：LLM / Embedding / ASR / TTS / Memos / 客户端偏好。
 * <p>
 * GET  /api/runtime-config          → 当前生效配置
 * PUT  /api/runtime-config          → 合并更新并持久化，立即生效
 * GET  /api/runtime-config/defaults → yml 启动默认值（用于重置参考）
 * GET  /api/runtime-config/tts-avatars?baseUrl=... → 查询当前 TTS 服务已注册音色
 */
@RestController
@RequestMapping("/api/runtime-config")
@RequiredArgsConstructor
public class RuntimeConfigController {

    private final RuntimeConfigService runtimeConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @GetMapping
    public ResponseEntity<RuntimeConfig> getConfig() {
        return ResponseEntity.ok(runtimeConfigService.getEffective());
    }

    @GetMapping("/defaults")
    public ResponseEntity<RuntimeConfig> getDefaults() {
        return ResponseEntity.ok(runtimeConfigService.getYmlDefaults());
    }

    @GetMapping("/tts-avatars")
    public ResponseEntity<?> getTtsAvatars(@RequestParam(value = "baseUrl", required = false) String baseUrl) throws Exception {
        String effectiveBaseUrl = StringUtils.hasText(baseUrl)
                ? baseUrl.trim()
                : runtimeConfigService.getEffective().getVoice().getAstraTtsBaseUrl();
        if (!StringUtils.hasText(effectiveBaseUrl)) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing baseUrl"));
        }

        String normalizedBaseUrl = effectiveBaseUrl.replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizedBaseUrl + "/api/tts/avatars"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return ResponseEntity.status(response.statusCode()).body(Map.of("error", response.body()));
        }

        JsonNode json = objectMapper.readTree(response.body());
        return ResponseEntity.ok(json);
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
