package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.config.LlmProperties;
import org.example.aichat.config.VoiceProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口，用于验证骨架 / 跨机依赖是否正常。
 * 探测地址读取运行时配置（支持热更新）。
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final LlmProperties llmProperties;
    private final VoiceProperties voiceProperties;

    private final HttpClient probe = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @GetMapping
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "AI-Chat",
                "timestamp", Instant.now().toString()
        );
    }

    /** 自检 LLM：GET {llm.base-url}/models */
    @GetMapping("/llm")
    public Map<String, Object> llm() {
        return probeHttp(llmProperties.getBaseUrl() + "/models");
    }

    /** 自检 ASR：GET {asr-url 去掉路径}/healthz */
    @GetMapping("/asr")
    public Map<String, Object> asr() {
        String asrUrl = voiceProperties.getAsrUrl();
        String base = asrUrl.replaceAll("/v1/audio/transcriptions$", "");
        return probeHttp(base + "/healthz");
    }

    /** 自检 TTS：GET {astra-tts-base-url}/health 或根路径 */
    @GetMapping("/tts")
    public Map<String, Object> tts() {
        String base = voiceProperties.getAstraTtsBaseUrl();
        Map<String, Object> health = probeHttp(base + "/health");
        if (Boolean.TRUE.equals(health.get("ok"))) {
            return health;
        }
        return probeHttp(base + "/");
    }

    private Map<String, Object> probeHttp(String url) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        long t0 = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = probe.send(req, HttpResponse.BodyHandlers.ofString());
            result.put("ok", resp.statusCode() >= 200 && resp.statusCode() < 300);
            result.put("status", resp.statusCode());
            String body = resp.body();
            result.put("bodyPreview", body == null ? "" : body.substring(0, Math.min(body.length(), 500)));
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            result.put("costMs", System.currentTimeMillis() - t0);
        }
        return result;
    }
}
