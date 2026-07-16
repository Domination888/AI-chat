package org.example.aichat.service.tts;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.aichat.config.VoiceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Astra TTS 引擎策略（Genie-TTS / AstraTTS）。
 * 使用 /api/tts/predict-stream GET 流式接口，返回 IEEE float32 LE PCM（单声道，32000Hz）。
 * 音色通过 avatarId 选择；未显式配置时会从当前 AstraTTS 服务的 /api/tts/avatars 自动发现。
 */
@Slf4j
@Component
public class AstraTtsStrategy implements TtsStrategy {

    @Autowired
    private VoiceProperties voiceProps;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, AvatarCache> avatarCache = new ConcurrentHashMap<>();

    @Override
    public String engineName() { return "astra"; }

    @Override
    public int outputSampleRate() { return 32000; }

    @Override
    public boolean isRawPcmOutput() { return true; }

    @Override
    public String pcmFormat() { return "pcm_f32le"; }

    @Override
    public InputStream tts(String text, String voiceId) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long n = ttsStream(text, voiceId, null, null, chunk -> {
            try { buf.write(chunk); } catch (Exception ignored) {}
        });
        if (n <= 0) return null;
        return new ByteArrayInputStream(buf.toByteArray());
    }

    @Override
    public long ttsStream(String text, String voiceId, Double speedFactor, Double pitchFactor, Consumer<byte[]> chunkConsumer) {
        if (text == null || text.trim().isEmpty()) return 0;

        // 解析 profile 获取 avatarId 和推理参数
        String avatarId = resolveAvatarId(voiceId);
        VoiceProperties.Profile profile = voiceProps.resolveProfile(voiceId);

        long t0 = System.currentTimeMillis();
        long total = 0;
        try {
            // 构造 /api/tts/predict-stream GET 查询参数
            StringBuilder sb = new StringBuilder(voiceProps.getAstraTtsBaseUrl());
            sb.append("/api/tts/predict-stream?text=");
            sb.append(URLEncoder.encode(text, StandardCharsets.UTF_8));

            if (StringUtils.hasText(avatarId)) {
                sb.append("&avatarId=").append(URLEncoder.encode(avatarId, StandardCharsets.UTF_8));
            }

            // 速度参数
            double speed = speedFactor != null ? speedFactor :
                    (profile != null ? profile.getSpeedFactor() : 1.0);
            if (speed != 1.0) {
                sb.append("&speed=").append(speed);
            }

            // temperature
            if (profile != null && profile.getTemperature() != 1.0) {
                sb.append("&temperature=").append(profile.getTemperature());
            }

            // topK
            if (profile != null && profile.getTopK() != 15) {
                sb.append("&topK=").append(profile.getTopK());
            }

            // noiseScale (映射自 profile 的 topP，如果有的话)
            // Astra 使用 noiseScale 而非 topP，暂不传，用服务端默认值

            // languages
            if (profile != null && StringUtils.hasText(profile.getTextLang())) {
                sb.append("&languages=").append(URLEncoder.encode(profile.getTextLang(), StandardCharsets.UTF_8));
            }

            // chunkSize：流式分片大小，默认 2048
            int chunkSize = voiceProps.getAstraStreamingChunkSize();
            if (chunkSize > 0) {
                sb.append("&chunkSize=").append(chunkSize);
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(sb.toString()))
                    .timeout(Duration.ofMillis(voiceProps.getTtsTimeoutMs()))
                    .header("Accept", "*/*")
                    .GET()
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("Astra TTS 返回非 200: status={}, body={}", resp.statusCode(), err);
                return -1;
            }

            byte[] tmp = new byte[8 * 1024];
            long firstChunkAt = -1;
            // Astra predict-stream 返回 IEEE float32 LE PCM，透传给前端
            try (InputStream in = resp.body()) {
                int n;
                while ((n = in.read(tmp)) > 0) {
                    if (firstChunkAt < 0) {
                        firstChunkAt = System.currentTimeMillis() - t0;
                    }
                    byte[] chunk = new byte[n];
                    System.arraycopy(tmp, 0, chunk, 0, n);
                    chunkConsumer.accept(chunk);
                    total += n;
                }
            }
            log.info("TTS ok: engine=astra, avatarId={}, bytes={}, cost={}ms", avatarId, total, System.currentTimeMillis() - t0);
            return total;
        } catch (Exception e) {
            log.error("Astra TTS 失败 (确保 Astra TTS 服务已启动: {})", voiceProps.getAstraTtsBaseUrl(), e);
            return -1;
        }
    }

    /**
     * 从 voiceId 映射到 Astra avatarId。
     * 优先使用当前服务存在的显式配置；显式配置缺失或已不在当前服务时，按角色自动发现。
     */
    private String resolveAvatarId(String voiceId) {
        String configuredDefault = voiceProps.getAstraDefaultAvatarId();
        if (StringUtils.hasText(configuredDefault) && avatarExistsOrUnknown(configuredDefault.trim())) {
            return configuredDefault.trim();
        }

        String discovered = discoverAvatarId(voiceId);
        if (StringUtils.hasText(discovered)) {
            return discovered;
        }

        VoiceProperties.Profile profile = voiceProps.resolveProfile(voiceId);
        if (profile != null && StringUtils.hasText(profile.getAstraAvatarId())) {
            String configured = profile.getAstraAvatarId().trim();
            if (avatarExistsOrUnknown(configured)) {
                return configured;
            }
            log.warn("Configured Astra avatarId '{}' is not available at {}, trying auto discovery",
                    configured, voiceProps.getAstraTtsBaseUrl());
        }

        return null;
    }

    private boolean avatarExistsOrUnknown(String avatarId) {
        AvatarCache cache = loadAvatars();
        return cache == null || cache.hasId(avatarId);
    }

    private String discoverAvatarId(String voiceId) {
        AvatarCache cache = loadAvatars();
        if (cache == null || cache.items().isEmpty()) {
            return null;
        }

        String normalizedVoiceId = voiceId == null ? "" : voiceId.trim().toLowerCase();
        if ("shu".equals(normalizedVoiceId)) {
            String byName = cache.findByName("黍");
            if (StringUtils.hasText(byName)) return byName;

            String byId = cache.findByIdContains("shu");
            if (StringUtils.hasText(byId)) return byId;
        }

        if (StringUtils.hasText(voiceId)) {
            String byId = cache.findByIdContains(voiceId.trim());
            if (StringUtils.hasText(byId)) return byId;
        }

        return null;
    }

    private AvatarCache loadAvatars() {
        String baseUrl = voiceProps.getAstraTtsBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            return null;
        }
        String normalizedBaseUrl = baseUrl.trim().replaceAll("/+$", "");
        AvatarCache cached = avatarCache.get(normalizedBaseUrl);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return cached.available() ? cached : null;
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(normalizedBaseUrl + "/api/tts/avatars"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                avatarCache.put(normalizedBaseUrl, AvatarCache.unavailable(now));
                return null;
            }

            JsonNode root = objectMapper.readTree(resp.body());
            AvatarCache cache = AvatarCache.fromJson(root, now);
            avatarCache.put(normalizedBaseUrl, cache);
            return cache;
        } catch (Exception e) {
            avatarCache.put(normalizedBaseUrl, AvatarCache.unavailable(now));
            log.debug("Astra avatar list unavailable at {}", normalizedBaseUrl, e);
            return null;
        }
    }

    private record Avatar(String id, String name) {}

    private record AvatarCache(boolean available, java.util.List<Avatar> items, long expiresAt) {
        private static AvatarCache fromJson(JsonNode root, long now) {
            java.util.List<Avatar> avatars = new java.util.ArrayList<>();
            if (root != null && root.isArray()) {
                for (JsonNode node : root) {
                    String id = node.path("id").asText("");
                    String name = node.path("name").asText("");
                    if (StringUtils.hasText(id)) {
                        avatars.add(new Avatar(id, name));
                    }
                }
            }
            return new AvatarCache(true, avatars, now + Duration.ofMinutes(1).toMillis());
        }

        private static AvatarCache unavailable(long now) {
            return new AvatarCache(false, java.util.List.of(), now + Duration.ofSeconds(10).toMillis());
        }

        private boolean hasId(String id) {
            return items.stream().anyMatch(a -> a.id().equals(id));
        }

        private String findByName(String name) {
            return items.stream()
                    .filter(a -> a.name().equals(name))
                    .map(Avatar::id)
                    .findFirst()
                    .orElse(null);
        }

        private String findByIdContains(String text) {
            String needle = text == null ? "" : text.toLowerCase();
            if (needle.isBlank()) return null;
            return items.stream()
                    .filter(a -> a.id().toLowerCase().contains(needle))
                    .map(Avatar::id)
                    .findFirst()
                    .orElse(null);
        }
    }
}
