package org.example.aichat.service.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * GPT-SoVITS api_v2 TTS 引擎策略。
 * raw PCM 输出（int16 LE，单声道，v2Pro=48000Hz）。
 */
@Slf4j
@Component
public class GptSovitsTtsStrategy implements TtsStrategy {

    @Autowired
    private VoiceProperties voiceProps;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 已经在 GPT-SoVITS 端切换过权重的 voiceId 集合 */
    private final Set<String> switchedVoiceIds = ConcurrentHashMap.newKeySet();

    @Override
    public String engineName() { return "gpt-sovits"; }

    @Override
    public int outputSampleRate() { return 48000; }

    @Override
    public boolean isRawPcmOutput() { return true; }

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
        VoiceProperties.Profile profile = voiceProps.resolveProfile(voiceId);
        if (profile == null) {
            log.warn("找不到 TTS profile: voiceId={}, 检查 voice.tts-profiles 配置", voiceId);
            return -1;
        }
        if (!StringUtils.hasText(profile.getRefAudioPath())) {
            log.warn("TTS profile 缺少 refAudioPath: voiceId={}", voiceId);
            return -1;
        }

        // 首次访问该 voiceId 时切换 GPT-SoVITS 模型权重
        if (voiceProps.isTtsAutoSwitchWeights()) {
            ensureWeightsSwitched(voiceId, profile);
        }

        long t0 = System.currentTimeMillis();
        long total = 0;
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("text", text);
            body.put("text_lang", profile.getTextLang());
            body.put("ref_audio_path", profile.getRefAudioPath());
            body.put("prompt_text", profile.getPromptText() == null ? "" : profile.getPromptText());
            body.put("prompt_lang", profile.getPromptLang());
            body.put("top_k", profile.getTopK());
            body.put("top_p", profile.getTopP());
            body.put("temperature", profile.getTemperature());
            body.put("speed_factor", speedFactor != null ? speedFactor : profile.getSpeedFactor());
            if (pitchFactor != null) {
                body.put("pitch_factor", pitchFactor);
            }
            body.put("fragment_interval", profile.getFragmentInterval());
            body.put("sample_steps", profile.getSampleSteps());
            body.put("text_split_method", profile.getTextSplitMethod());
            body.put("media_type", "raw");
            body.put("streaming_mode", voiceProps.getTtsStreamingMode());
            body.put("parallel_infer", true);
            body.put("batch_size", 1);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(voiceProps.getTtsBaseUrl() + "/tts"))
                    .timeout(Duration.ofMillis(voiceProps.getTtsTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "*/*")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("TTS 返回非 200: status={}, body={}", resp.statusCode(), err);
                return -1;
            }

            byte[] tmp = new byte[8 * 1024];
            long firstChunkAt = -1;
            try (InputStream in = resp.body()) {
                int n;
                while ((n = in.read(tmp)) > 0) {
                    if (firstChunkAt < 0) {
                        firstChunkAt = System.currentTimeMillis() - t0;
                    }
                    byte[] copy = new byte[n];
                    System.arraycopy(tmp, 0, copy, 0, n);
                    chunkConsumer.accept(copy);
                    total += n;
                }
            }
            log.info("TTS ok: engine=gpt-sovits, voiceId={}, bytes={}, cost={}ms", voiceId, total, System.currentTimeMillis() - t0);
            return total;
        } catch (Exception e) {
            log.error("TTS 失败 (确保 GPT-SoVITS api_v2 已启动: {}/tts)", voiceProps.getTtsBaseUrl(), e);
            return -1;
        }
    }

    private void ensureWeightsSwitched(String voiceId, VoiceProperties.Profile profile) {
        String key = voiceId == null ? "_default_" : voiceId;
        if (switchedVoiceIds.contains(key)) return;
        synchronized (switchedVoiceIds) {
            if (switchedVoiceIds.contains(key)) return;
            try {
                if (StringUtils.hasText(profile.getGptWeights())) {
                    callWeightsApi("/set_gpt_weights", "weights_path", profile.getGptWeights());
                }
                if (StringUtils.hasText(profile.getSovitsWeights())) {
                    callWeightsApi("/set_sovits_weights", "weights_path", profile.getSovitsWeights());
                }
                switchedVoiceIds.add(key);
                log.info("TTS 权重切换完成: voiceId={}, gpt={}, sovits={}", voiceId, profile.getGptWeights(), profile.getSovitsWeights());
            } catch (Exception e) {
                log.warn("TTS 权重切换失败（不影响后续 /tts，但可能音色不准），voiceId={}", voiceId, e);
            }
        }
    }

    private void callWeightsApi(String path, String paramName, String value) throws Exception {
        String url = voiceProps.getTtsBaseUrl() + path
                + "?" + paramName + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("weights api 失败: " + resp.statusCode() + " body=" + resp.body());
        }
    }
}