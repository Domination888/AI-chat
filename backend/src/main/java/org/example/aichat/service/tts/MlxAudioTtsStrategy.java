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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * MLX-Audio + Qwen3-TTS 引擎策略。
 * OpenAI 兼容 API：POST /v1/audio/speech
 * 输出格式：WAV（完整文件头），采样率 24000Hz
 */
@Slf4j
@Component
public class MlxAudioTtsStrategy implements TtsStrategy {

    @Autowired
    private VoiceProperties voiceProps;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String engineName() { return "mlx-audio"; }

    @Override
    public int outputSampleRate() { return voiceProps.getMlxAudioSampleRate(); }

    @Override
    public boolean isRawPcmOutput() {
        // 流式 + pcm：各 chunk 为可拼接的 s16le，前端可边收边播
        return voiceProps.isMlxAudioStream();
    }

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

        long t0 = System.currentTimeMillis();
        long total = 0;
        try {
            // 构造 OpenAI 兼容 SpeechRequest
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", voiceProps.getMlxAudioModel());
            body.put("input", text);
            // voice：默认走 ref_audio 克隆；mlx-audio-use-preset-voice=true 时用内置音色测速
            boolean usePreset = voiceProps.isMlxAudioUsePresetVoice();
            if (usePreset) {
                body.put("voice", voiceProps.getMlxAudioPresetVoice());
            } else if (profile != null && StringUtils.hasText(profile.getRefAudioPath())) {
                body.put("ref_audio", profile.getRefAudioPath());
                if (StringUtils.hasText(profile.getPromptText())) {
                    body.put("ref_text", profile.getPromptText());
                }
            } else {
                body.put("voice", voiceProps.getMlxAudioPresetVoice());
            }
            // 语言：从 profile 取 textLang，默认 zh
            String langCode = (profile != null && StringUtils.hasText(profile.getTextLang()))
                    ? profile.getTextLang() : "zh";
            body.put("lang_code", langCode);
            // 推理参数
            if (speedFactor != null) body.put("speed", speedFactor);
            else if (profile != null) body.put("speed", profile.getSpeedFactor());
            if (profile != null) {
                body.put("temperature", profile.getTemperature());
                body.put("top_p", profile.getTopP());
                body.put("top_k", profile.getTopK());
            }
            // 按文本长度收紧上限，避免短句占用过多 token 步数
            int maxTokens = Math.min(1200, Math.max(256, text.length() * 22 + 128));
            body.put("max_tokens", maxTokens);
            // 流式时用 raw PCM（chunk 可拼接）；非流式用完整 WAV
            boolean stream = voiceProps.isMlxAudioStream();
            body.put("stream", stream);
            if (stream) {
                body.put("streaming_interval", voiceProps.getMlxAudioStreamingInterval());
                body.put("response_format", "pcm");
            } else {
                body.put("response_format", "wav");
            }

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            log.debug("MLX-Audio TTS request body: {}", body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(voiceProps.getMlxAudioBaseUrl() + "/v1/audio/speech"))
                    .timeout(Duration.ofMillis(voiceProps.getTtsTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "*/*")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("MLX-Audio TTS 返回非 200: status={}, body={}", resp.statusCode(), err);
                return -1;
            }

            byte[] tmp = new byte[8 * 1024];
            long firstChunkAt = -1;
            // PCM s16le 必须按 2 字节对齐；HTTP 8KB 分片可能在样本中间切断
            boolean pcmStream = stream && "pcm".equals(body.get("response_format").asText());
            byte pendingByte = 0;
            boolean hasPending = false;
            try (InputStream in = resp.body()) {
                int n;
                while ((n = in.read(tmp)) > 0) {
                    int offset = 0;
                    if (pcmStream && hasPending) {
                        if (n == 0) break;
                        byte[] pair = new byte[] { pendingByte, tmp[0] };
                        if (firstChunkAt < 0) {
                            firstChunkAt = System.currentTimeMillis() - t0;
                        }
                        chunkConsumer.accept(pair);
                        total += 2;
                        offset = 1;
                        hasPending = false;
                    } else if (firstChunkAt < 0) {
                        firstChunkAt = System.currentTimeMillis() - t0;
                    }
                    int avail = n - offset;
                    if (avail <= 0) continue;
                    int emitLen = pcmStream ? (avail & ~1) : avail;
                    if (emitLen > 0) {
                        byte[] copy = new byte[emitLen];
                        System.arraycopy(tmp, offset, copy, 0, emitLen);
                        chunkConsumer.accept(copy);
                        total += emitLen;
                    }
                    if (pcmStream && (avail & 1) == 1) {
                        pendingByte = tmp[offset + emitLen];
                        hasPending = true;
                    }
                }
                if (pcmStream && hasPending) {
                    chunkConsumer.accept(new byte[] { pendingByte, 0 });
                    total += 2;
                }
            }
            log.info("TTS ok: engine=mlx-audio, voiceId={}, bytes={}, cost={}ms", voiceId, total, System.currentTimeMillis() - t0);
            return total;
        } catch (Exception e) {
            log.error("MLX-Audio TTS 失败 (确保 MLX-Audio 服务已启动: {})", voiceProps.getMlxAudioBaseUrl(), e);
            return -1;
        }
    }
}