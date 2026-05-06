package org.example.aichat.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.aichat.service.VoiceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Slf4j
@Service
public class VoiceServiceImpl implements VoiceService {

    @Value("${voice.asr-url}")
    private String asrUrl;

    @Value("${voice.tts-url}")
    private String ttsUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String asr(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            return "";
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(audioFile.getBytes()) {
                @Override
                public String getFilename() {
                    return audioFile.getOriginalFilename() != null ? audioFile.getOriginalFilename() : "audio.wav";
                }
            });
            
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(asrUrl, requestEntity, String.class);
            
            // 假设 SenseVoice 接口返回纯文本或JSON中包含 text。这里需要根据实际API调整
            // 简化处理，直接返回或解析JSON
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("ASR 识别结果: {}", response.getBody());
                return response.getBody(); // 如果是JSON需解析
            }
        } catch (Exception e) {
            log.error("ASR 语音转文字失败 (请确保本地 SenseVoice 服务已启动)", e);
        }
        return "";
    }

    @Override
    public InputStream tts(String text, String voiceId) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            // GPT-SoVITS 默认 GET API 示例: ?text=你好&text_language=zh
            // 您可能需要根据实际传入的 voiceId 来映射到 GPT-SoVITS 支持的音色配置参数
            String url = ttsUrl + "?text=" + java.net.URLEncoder.encode(text, "UTF-8") 
                       + "&text_language=zh";
                       
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            
            if (response.statusCode() == 200 && response.body() != null) {
                return new ByteArrayInputStream(response.body());
            } else {
                log.error("TTS 返回状态异常: {}", response.statusCode());
            }
        } catch (Exception e) {
            log.error("TTS 文字转语音失败 (请确保本地 GPT-SoVITS 服务已启动)", e);
        }
        return null;
    }
}
