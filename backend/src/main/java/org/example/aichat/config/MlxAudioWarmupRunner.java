package org.example.aichat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * 启动时预热 MLX-Audio 参考音 zeroprompt 缓存（黍等 ref_audio 角色）。
 */
@Slf4j
@Component
public class MlxAudioWarmupRunner implements ApplicationRunner {

    @Autowired
    private VoiceProperties voiceProps;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void run(ApplicationArguments args) {
        if (!"mlx-audio".equals(voiceProps.getTtsEngine())) {
            return;
        }
        if (!voiceProps.isMlxAudioWarmOnStart() || voiceProps.isMlxAudioUsePresetVoice()) {
            return;
        }

        Set<String> warmed = new HashSet<>();
        for (var entry : voiceProps.getTtsProfiles().entrySet()) {
            VoiceProperties.Profile profile = voiceProps.resolveProfile(entry.getKey());
            if (profile == null || !StringUtils.hasText(profile.getRefAudioPath())) {
                continue;
            }
            String path = profile.getRefAudioPath();
            if (!warmed.add(path)) {
                continue;
            }
            warm(path, profile.getPromptText());
        }
    }

    private void warm(String refAudioPath, String refText) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("ref_audio", refAudioPath);
            body.put("ref_text", refText == null ? "" : refText);
            body.put("model", voiceProps.getMlxAudioModel());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(voiceProps.getMlxAudioBaseUrl() + "/v1/audio/warm-voice"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                log.info("MLX-Audio warm-voice ok: path={}, body={}", refAudioPath, resp.body());
            } else {
                log.warn("MLX-Audio warm-voice failed: status={}, path={}, body={}",
                        resp.statusCode(), refAudioPath, resp.body());
            }
        } catch (Exception e) {
            log.warn("MLX-Audio warm-voice skipped (TTS 服务可能尚未启动): path={}, err={}",
                    refAudioPath, e.getMessage());
        }
    }
}
