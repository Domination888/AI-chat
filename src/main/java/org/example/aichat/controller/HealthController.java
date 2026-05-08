package org.example.aichat.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Value("${llm.base-url}")
    private String llmBaseUrl;

    @Value("${voice.asr-url}")
    private String asrUrl;

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

    /**
     * 自检 Win LM Studio：GET {llm.base-url}/models
     */
    @GetMapping("/llm")
    public Map<String, Object> llm() {
        return probeHttp(llmBaseUrl + "/models");
    }

    /**
     * 自检本机 SenseVoice：GET http://127.0.0.1:9000/healthz
     */
    @GetMapping("/asr")
    public Map<String, Object> asr() {
        String base = asrUrl.replaceAll("/v1/audio/transcriptions$", "");
        return probeHttp(base + "/healthz");
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