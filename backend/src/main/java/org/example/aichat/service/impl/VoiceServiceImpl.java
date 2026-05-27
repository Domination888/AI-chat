package org.example.aichat.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.config.VoiceProperties;
import org.example.aichat.service.VoiceService;
import org.example.aichat.service.tts.TtsStrategy;
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

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VoiceServiceImpl implements VoiceService {

    @Autowired
    private VoiceProperties voiceProps;

    /** 所有 TTS 策略实现，按 engineName 索引 */
    private Map<String, TtsStrategy> strategyMap;

    @Autowired
    private List<TtsStrategy> strategies;

    private RestTemplate asrRestTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initAsrClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(voiceProps.getAsrTimeoutMs());
        this.asrRestTemplate = new RestTemplate(factory);

        // 构建 strategyMap
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(TtsStrategy::engineName, s -> s));

        log.info("ASR client ready: url={}, language={}, timeoutMs={}",
                voiceProps.getAsrUrl(), voiceProps.getAsrLanguage(), voiceProps.getAsrTimeoutMs());
        log.info("TTS engine: {}, available strategies: {}, profiles: {}",
                voiceProps.getTtsEngine(), strategyMap.keySet(), voiceProps.getTtsProfiles().keySet());
    }

    /**
     * 获取当前 TTS 策略
     */
    private TtsStrategy currentStrategy() {
        String engine = voiceProps.getTtsEngine();
        TtsStrategy strategy = strategyMap.get(engine);
        if (strategy == null) {
            log.warn("未知的 TTS 引擎: {}，回退到 gpt-sovits", engine);
            strategy = strategyMap.get("gpt-sovits");
        }
        return strategy;
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
        final String origName = audioFile.getOriginalFilename();
        final String langUsed = StringUtils.hasText(language) ? language : voiceProps.getAsrLanguage();
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
            ResponseEntity<String> resp = asrRestTemplate.postForEntity(voiceProps.getAsrUrl(), req, String.class);

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("ASR 响应非 2xx: status={}, body={}", resp.getStatusCode(), resp.getBody());
                return "";
            }
            String text = parseAsrText(resp.getBody());
            return text;
        } catch (Exception e) {
            log.error("ASR 调用失败 (请确保 SenseVoice 已启动: {})", voiceProps.getAsrUrl(), e);
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
    // TTS — 委派给 TtsStrategy
    // =========================================================
    @Override
    public InputStream tts(String text, String voiceId) {
        return currentStrategy().tts(text, voiceId);
    }

    @Override
    public long ttsStream(String text, String voiceId, Consumer<byte[]> chunkConsumer) {
        return currentStrategy().ttsStream(text, voiceId, null, null, chunkConsumer);
    }

    @Override
    public long ttsStreamWithParams(String text, String voiceId, Double speedFactor, Consumer<byte[]> chunkConsumer) {
        return currentStrategy().ttsStream(text, voiceId, speedFactor, null, chunkConsumer);
    }

    @Override
    public long ttsStreamWithFullParams(String text, String voiceId, Double speedFactor, Double pitchFactor, Consumer<byte[]> chunkConsumer) {
        return currentStrategy().ttsStream(text, voiceId, speedFactor, pitchFactor, chunkConsumer);
    }

    @Override
    public int currentTtsSampleRate() {
        return currentStrategy().outputSampleRate();
    }

    @Override
    public String currentTtsFormat() {
        return currentStrategy().isRawPcmOutput() ? "pcm_s16le" : "wav";
    }
}