package org.example.aichat.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.config.VoiceProperties;
import org.example.aichat.service.VoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
public class VoiceServiceImpl implements VoiceService {

    /** 专用模块日志：写入 log/asr/asr.log（见 logback-spring.xml） */
    private static final Logger ASR_LOG = LoggerFactory.getLogger("module.asr");
    /** 专用模块日志：写入 log/tts/tts.log（见 logback-spring.xml） */
    private static final Logger TTS_LOG = LoggerFactory.getLogger("module.tts");

    @Autowired
    private VoiceProperties voiceProps;

    private RestTemplate asrRestTemplate;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   // GPT-SoVITS uvicorn 默认只跑 HTTP/1.1，避免 HTTP/2 协商失败
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 已经在 GPT-SoVITS 端切换过权重的 voiceId 集合，避免每次请求都切 */
    private final Set<String> switchedVoiceIds = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void initAsrClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(voiceProps.getAsrTimeoutMs());
        this.asrRestTemplate = new RestTemplate(factory);
        log.info("ASR client ready: url={}, language={}, timeoutMs={}",
                voiceProps.getAsrUrl(), voiceProps.getAsrLanguage(), voiceProps.getAsrTimeoutMs());
        log.info("TTS client ready: baseUrl={}, profiles={}",
                voiceProps.getTtsBaseUrl(), voiceProps.getTtsProfiles().keySet());
    }

    // =========================================================
    // ASR
    // =========================================================
    @Override
    public String asr(MultipartFile audioFile) {
        return asr(audioFile, null, null);
    }

    @Override
    public String asr(MultipartFile audioFile, String hotwords, String language) {
        if (audioFile == null || audioFile.isEmpty()) return "";
        final long sizeBytes = audioFile.getSize();
        final String origName = audioFile.getOriginalFilename();
        final String contentType = audioFile.getContentType();
        final String langUsed = StringUtils.hasText(language) ? language : voiceProps.getAsrLanguage();
        ASR_LOG.info("ASR start | url={} | file={} | size={}B | contentType={} | language={} | hotwords={}",
                voiceProps.getAsrUrl(), origName, sizeBytes, contentType, langUsed,
                StringUtils.hasText(hotwords) ? hotwords : "");
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(audioFile.getBytes()) {
                @Override public String getFilename() {
                    return StringUtils.hasText(origName) ? origName : "audio.webm";
                }
            });
            body.add("language", langUsed);
            if (StringUtils.hasText(hotwords)) body.add("hotwords", hotwords);

            HttpEntity<MultiValueMap<String, Object>> req = new HttpEntity<>(body, headers);
            long t0 = System.currentTimeMillis();
            ResponseEntity<String> resp = asrRestTemplate.postForEntity(voiceProps.getAsrUrl(), req, String.class);
            long cost = System.currentTimeMillis() - t0;

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("ASR 响应非 2xx: status={}, body={}", resp.getStatusCode(), resp.getBody());
                ASR_LOG.warn("ASR fail | status={} | cost={}ms | body={}",
                        resp.getStatusCode(), cost, resp.getBody());
                return "";
            }
            String text = parseAsrText(resp.getBody());
            log.info("ASR ok: cost={}ms, text={}", cost, text);
            ASR_LOG.info("ASR ok | cost={}ms | textLen={} | text={}", cost, text.length(), text);
            return text;
        } catch (Exception e) {
            log.error("ASR 调用失败 (请确保 SenseVoice 已启动: {})", voiceProps.getAsrUrl(), e);
            ASR_LOG.error("ASR error | url={} | file={} | size={}B | err={}",
                    voiceProps.getAsrUrl(), origName, sizeBytes, e.toString(), e);
            return "";
        }
    }

    private String parseAsrText(String body) {
        if (!StringUtils.hasText(body)) return "";
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node != null && node.hasNonNull("text")) return node.get("text").asText("").trim();
        } catch (Exception ignored) { }
        return body.trim();
    }

    // =========================================================
    // TTS
    // =========================================================
    @Override
    public InputStream tts(String text, String voiceId) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long n = ttsStream(text, voiceId, chunk -> {
            try { buf.write(chunk); } catch (Exception ignored) {}
        });
        if (n <= 0) return null;
        return new ByteArrayInputStream(buf.toByteArray());
    }

    @Override
    public long ttsStream(String text, String voiceId, Consumer<byte[]> chunkConsumer) {
        if (text == null || text.trim().isEmpty()) return 0;
        VoiceProperties.Profile profile = voiceProps.resolveProfile(voiceId);
        if (profile == null) {
            log.warn("找不到 TTS profile: voiceId={}, 检查 voice.tts-profiles 配置", voiceId);
            TTS_LOG.warn("TTS skip | voiceId={} | reason=profile_not_found", voiceId);
            return -1;
        }
        if (!StringUtils.hasText(profile.getRefAudioPath())) {
            log.warn("TTS profile 缺少 refAudioPath: voiceId={}", voiceId);
            TTS_LOG.warn("TTS skip | voiceId={} | reason=missing_ref_audio", voiceId);
            return -1;
        }

        // 首次访问该 voiceId 时切换 GPT-SoVITS 模型权重（异步切换 + 后续请求复用）
        if (voiceProps.isTtsAutoSwitchWeights()) {
            ensureWeightsSwitched(voiceId, profile);
        }

        long t0 = System.currentTimeMillis();
        long total = 0;
        TTS_LOG.info("TTS start | voiceId={} | profile=[refAudio={}, promptText={}, lang={}, topK={}, topP={}, temp={}, speed={}, sampleSteps={}, splitMethod={}, streamingMode={}] | textLen={} | text={}",
                voiceId, profile.getRefAudioPath(), profile.getPromptText(),
                profile.getTextLang(), profile.getTopK(), profile.getTopP(),
                profile.getTemperature(), profile.getSpeedFactor(), profile.getSampleSteps(),
                profile.getTextSplitMethod(), voiceProps.getTtsStreamingMode(),
                text.length(), text);
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
            body.put("speed_factor", profile.getSpeedFactor());
            body.put("fragment_interval", profile.getFragmentInterval());
            // v2Pro/v3/v4 关键参数：webui 默认 8，api_v2 默认 32 —— 漏传会慢 4 倍 + 音色漂
            body.put("sample_steps", profile.getSampleSteps());
            // webui "凑四句一切" = cut1（不是 cut5；cut5 是按标点切，会让黍模型断句异常）
            body.put("text_split_method", profile.getTextSplitMethod());
            // raw PCM（int16 LE，单声道，32000Hz for v2Pro/v2/v4）
            // 不发 wav，避免 streaming wav 多 RIFF header 浏览器 decodeAudioData 失败的坑；
            // 前端拿 PCM 直接造 AudioBuffer，零解码开销。
            body.put("media_type", "raw");
            // streaming_mode：1=最高质量 2=中等 3=最快
            body.put("streaming_mode", voiceProps.getTtsStreamingMode());
            body.put("parallel_infer", true);
            body.put("batch_size", 1);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            log.debug("TTS request body: {} bytes, preview={}", payload.length,
                    body.toString().length() > 200 ? body.toString().substring(0, 200) + "..." : body.toString());

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
                TTS_LOG.error("TTS http-fail | voiceId={} | status={} | body={}", voiceId, resp.statusCode(), err);
                return -1;
            }

            byte[] tmp = new byte[8 * 1024];
            long firstChunkAt = -1;
            try (InputStream in = resp.body()) {
                int n;
                while ((n = in.read(tmp)) > 0) {
                    if (firstChunkAt < 0) {
                        firstChunkAt = System.currentTimeMillis() - t0;
                        log.info("TTS first-byte: voiceId={}, ttfb={}ms", voiceId, firstChunkAt);
                        TTS_LOG.info("TTS first-byte | voiceId={} | ttfb={}ms", voiceId, firstChunkAt);
                    }
                    byte[] copy = new byte[n];
                    System.arraycopy(tmp, 0, copy, 0, n);
                    chunkConsumer.accept(copy);
                    total += n;
                }
            }
            log.info("TTS ok: voiceId={}, textLen={}, bytes={}, ttfb={}ms, cost={}ms",
                    voiceId, text.length(), total, firstChunkAt, System.currentTimeMillis() - t0);
            TTS_LOG.info("TTS ok | voiceId={} | textLen={} | bytes={} | ttfb={}ms | cost={}ms",
                    voiceId, text.length(), total, firstChunkAt, System.currentTimeMillis() - t0);
            return total;
        } catch (Exception e) {
            log.error("TTS 失败 (确保 GPT-SoVITS api_v2 已启动: {}/tts), text={}",
                    voiceProps.getTtsBaseUrl(), abbr(text), e);
            TTS_LOG.error("TTS error | voiceId={} | textLen={} | text={} | err={}",
                    voiceId, text.length(), abbr(text), e.toString(), e);
            return -1;
        }
    }

    /**
     * 首次使用某 voiceId 时调用 /set_gpt_weights 与 /set_sovits_weights 切换权重。
     * 后续请求短路；切换失败不阻塞 TTS（GPT-SoVITS 会用当前权重）。
     */
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
                log.info("TTS 权重切换完成: voiceId={}, gpt={}, sovits={}",
                        voiceId, profile.getGptWeights(), profile.getSovitsWeights());
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

    private static String abbr(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}