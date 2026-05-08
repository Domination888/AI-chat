package org.example.aichat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.dto.ChatRequest;
import org.example.aichat.service.ChatService;
import org.example.aichat.service.SentenceSplitter;
import org.example.aichat.service.VoiceService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/audio")
public class AudioController {

    private final VoiceService voiceService;
    private final ChatService chatService;
    private final ObjectMapper om = new ObjectMapper();

    // ==========================================================
    // 1) 仅 ASR：录音转文字（保持原有行为）
    // ==========================================================
    @PostMapping(value = "/asr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> asrOnly(
            @RequestParam("file") MultipartFile audioFile,
            @RequestParam(value = "hotwords", required = false) String hotwords,
            @RequestParam(value = "language", required = false) String language) {
        Map<String, Object> resp = new HashMap<>();
        if (audioFile == null || audioFile.isEmpty()) {
            resp.put("ok", false);
            resp.put("error", "empty audio");
            return ResponseEntity.badRequest().body(resp);
        }
        String text = voiceService.asr(audioFile, hotwords, language);
        resp.put("ok", text != null && !text.trim().isEmpty());
        resp.put("text", text == null ? "" : text);
        return ResponseEntity.ok(resp);
    }

    // ==========================================================
    // 2) 单句 TTS（流式 wav）：前端按句子拉
    //    GET /api/audio/tts?text=...&voiceId=shu
    // ==========================================================
    @GetMapping(value = "/tts")
    public ResponseEntity<InputStreamResource> ttsOnly(
            @RequestParam("text") String text,
            @RequestParam(value = "voiceId", required = false) String voiceId) {
        InputStream in = voiceService.tts(text, voiceId);
        if (in == null) {
            return ResponseEntity.internalServerError().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("audio/wav"));
        headers.setCacheControl("no-cache");
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(in));
    }

    // ==========================================================
    // 3) 完整语音对话（阻塞）：保留以兼容旧前端
    //    上传录音 -> ASR -> LLM(阻塞) -> TTS -> 返回完整 wav
    // ==========================================================
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> audioChat(
            @RequestParam("file") MultipartFile audioFile,
            @RequestParam("conversationId") String conversationId,
            @RequestParam("roleId") Integer roleId,
            @RequestParam("voiceId") String voiceId,
            @RequestParam("userId") Integer userId,
            @RequestParam(value = "hotwords", required = false) String hotwords,
            @RequestParam(value = "language", required = false) String language) {

        String userText = voiceService.asr(audioFile, hotwords, language);
        if (userText == null || userText.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("无法识别音频内容");
        }
        String aiResponseText = chatService.chatBlocking(conversationId, userText, userId, roleId);
        InputStream audioStream = voiceService.tts(aiResponseText, voiceId);
        if (audioStream == null) {
            return ResponseEntity.internalServerError().body("TTS 转换失败");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("audio/wav"));
        headers.add("X-AI-Response-Text",
                URLEncoder.encode(aiResponseText, StandardCharsets.UTF_8));
        headers.add("X-ASR-Text",
                URLEncoder.encode(userText, StandardCharsets.UTF_8));
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(audioStream));
    }

    // ==========================================================
    // 4) 全流式语音对话（推荐）：
    //    上传录音 -> SSE
    //      event: asr   data: {"text":"用户说的话"}
    //      event: text  data: {"delta":"模型新token"}
    //      event: tts   data: {"idx":0,"text":"句子","audioBase64":"<wav>"}  // 边吐字边出语音
    //      event: done  data: {}
    //    前端：text 直接拼成气泡；tts 收到就排队 base64 解码 -> 串行播放
    // ==========================================================
    @PostMapping(value = "/chat-stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> audioChatStream(
            @RequestParam("file") MultipartFile audioFile,
            @RequestParam("conversationId") String conversationId,
            @RequestParam("roleId") Integer roleId,
            @RequestParam("voiceId") String voiceId,
            @RequestParam("userId") Integer userId,
            @RequestParam(value = "hotwords", required = false) String hotwords,
            @RequestParam(value = "language", required = false) String language) {

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 1) 先做 ASR（阻塞，几百 ms）
        String userText = voiceService.asr(audioFile, hotwords, language);
        if (userText == null || userText.trim().isEmpty()) {
            sink.tryEmitNext(sse("error", "{\"message\":\"无法识别音频内容\"}"));
            sink.tryEmitComplete();
            return sink.asFlux();
        }
        sink.tryEmitNext(sse("asr", json("text", userText)));

        // 2) 启动 LLM 流式 + 句子级 TTS（异步线程，避免阻塞 SSE 发射）
        ChatRequest req = new ChatRequest();
        req.setConversationId(conversationId);
        req.setUserId(String.valueOf(userId));
        req.setMessage(userText);
        req.setRoleId(roleId);
        // 语音通道：
        //  - 不带工具（绕开 LM Studio Gemma3 的 jinja 模板 bug + 降延迟）
        //  - 默认不开联网搜索/RAG（语音对话追求低延迟，要开可后续扩展 query 参数）
        req.setTools(false);
        req.setSearch(false);
        req.setRag(false);

        SentenceSplitter splitter = new SentenceSplitter(10, 80);
        AtomicInteger ttsIdx = new AtomicInteger(0);

        Flux<String> llmFlux = chatService.chatStream(req);

        llmFlux
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(token -> {
                    // 文本流：原样推给前端
                    sink.tryEmitNext(sse("text", json("delta", token)));
                    // 攒句子，攒齐就丢给 TTS（内部串行执行，避免 GPT-SoVITS 并发爆显存/内存）
                    List<String> sentences = splitter.append(token);
                    for (String s : sentences) {
                        emitTts(sink, s, voiceId, ttsIdx.getAndIncrement());
                    }
                })
                .doOnComplete(() -> {
                    String tail = splitter.flushRemainder();
                    if (tail != null && !tail.isBlank()) {
                        emitTts(sink, tail, voiceId, ttsIdx.getAndIncrement());
                    }
                    sink.tryEmitNext(sse("done", "{}"));
                    sink.tryEmitComplete();
                })
                .doOnError(err -> {
                    log.error("chat-stream LLM 失败", err);
                    sink.tryEmitNext(sse("error", json("message", String.valueOf(err.getMessage()))));
                    sink.tryEmitComplete();
                })
                .subscribe();

        return sink.asFlux();
    }

    /**
     * 拿到一整句话后调 TTS，把 wav 字节 base64 一次性塞进 SSE。
     * 这里串行调用：每句之间等上一句 wav 收完，避免对 GPT-SoVITS（CPU/MPS 推理）
     * 造成并发压力（M4 + 32GB 跑大模型推理本来就紧张）。
     */
    private void emitTts(Sinks.Many<ServerSentEvent<String>> sink,
                         String sentence, String voiceId, int idx) {
        try {
            log.info("TTS sentence#{}: {}", idx, sentence);
            // 用 ByteArrayOutputStream 同步等完一句（GPT-SoVITS 内部已是流式生成）
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            long n = voiceService.ttsStream(sentence, voiceId, chunk -> {
                try { buf.write(chunk); } catch (Exception ignored) {}
            });
            if (n <= 0) {
                sink.tryEmitNext(sse("tts", jsonObj(Map.of(
                        "idx", idx,
                        "text", sentence,
                        "error", "tts_failed"))));
                return;
            }
            String b64 = Base64.getEncoder().encodeToString(buf.toByteArray());
            sink.tryEmitNext(sse("tts", jsonObj(Map.of(
                    "idx", idx,
                    "text", sentence,
                    "audioBase64", b64))));
        } catch (Exception e) {
            log.error("emitTts 失败 idx={} sentence={}", idx, sentence, e);
            sink.tryEmitNext(sse("tts", jsonObj(Map.of(
                    "idx", idx,
                    "text", sentence,
                    "error", e.getMessage() == null ? "unknown" : e.getMessage()))));
        }
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder()
                .event(event)
                .data(data)
                .build();
    }

    private String json(String key, String val) {
        try {
            return om.writeValueAsString(Map.of(key, val == null ? "" : val));
        } catch (Exception e) { return "{}"; }
    }

    private String jsonObj(Map<String, Object> m) {
        try { return om.writeValueAsString(m); }
        catch (Exception e) { return "{}"; }
    }
}