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
            @RequestParam(value = "voiceId", required = false) String voiceId,
            @RequestParam(value = "speedFactor", required = false) Double speedFactor) {
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
        String aiResponseText = chatService.chatBlockingWithModel(conversationId, userText, userId, roleId, null, null);
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
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "modelBaseUrl", required = false) String modelBaseUrl,
            @RequestParam(value = "modelName", required = false) String modelName,
            @RequestParam(value = "ttsSpeedFactor", required = false) Double ttsSpeedFactor,
            @RequestParam(value = "ttsPitchFactor", required = false) Double ttsPitchFactor) {

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
        // 语音通道开关策略（与文字通道差异化）：
        //  - tools  = false：保首包延迟（语音 TTFB 敏感），并兜底 LM Studio 部分 Gemma jinja 模板带 tools 时的渲染异常
        //  - search = false：语音输入不主动联网，避免不可控的延迟尖峰
        //  - rag    = true ：保留 RAG，否则角色设定/长期记忆会丢，AI 答非所问
        req.setTools(false);
        req.setSearch(false);
        req.setRag(true);
        
        // 传递动态模型配置
        req.setModelBaseUrl(modelBaseUrl);
        req.setModelName(modelName);
        
        // 传递ASR/TTS参数
        req.setAsrLanguage(language);
        req.setAsrHotwords(hotwords);
        req.setTtsVoiceId(voiceId);
        req.setTtsSpeedFactor(ttsSpeedFactor);
        req.setTtsPitchFactor(ttsPitchFactor);

        // 语音场景优先首包速度：缩短最小句长，避免等太久才触发首句 TTS
        SentenceSplitter splitter = new SentenceSplitter(6, 48);
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
                        emitTts(sink, s, voiceId, ttsSpeedFactor, ttsPitchFactor, ttsIdx.getAndIncrement());
                    }
                })
                .doOnComplete(() -> {
                    String tail = splitter.flushRemainder();
                    if (tail != null && !tail.isBlank()) {
                        emitTts(sink, tail, voiceId, ttsSpeedFactor, ttsPitchFactor, ttsIdx.getAndIncrement());
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
     * 拿到一整句话后调 TTS，chunk 到就立即推给前端 SSE（真·流式）。
     * 协议扩展：单句会产生多条 tts 事件
     *   - event: tts   data: {"idx":N,"text":"...","seq":0,"audioBase64":"<chunk>","chunkStart":true}
     *   - event: tts   data: {"idx":N,"seq":1,"audioBase64":"<chunk>"}
     *   - event: tts   data: {"idx":N,"seq":K,"audioBase64":"<chunk>","chunkEnd":true,"bytes":NNN,"costMs":XX}
     * 前端按 idx 归类、按 seq 顺序拼装 Blob 播放。
     *
     * 串行调用：每句之间等上一句收完再调下一句，避免对 GPT-SoVITS 并发施压
     * （M4 + 32GB 同时还要跑业务后端，推理并发会直接抖）。
     */
    private void emitTts(Sinks.Many<ServerSentEvent<String>> sink,
                         String sentence, String voiceId, Double speedFactor, Double pitchFactor, int idx) {
        final long t0 = System.currentTimeMillis();
        final AtomicInteger seq = new AtomicInteger(0);
        final long[] firstChunkCost = {-1};
        try {
            log.info("TTS sentence#{} start: {}", idx, abbr(sentence));
            long n = voiceService.ttsStreamWithFullParams(sentence, voiceId, speedFactor, pitchFactor, chunk -> {



                int s = seq.getAndIncrement();
                if (s == 0) {
                    firstChunkCost[0] = System.currentTimeMillis() - t0;
                    log.info("TTS sentence#{} first-chunk: bytes={}, ttfb={}ms", idx, chunk.length, firstChunkCost[0]);
                }
                String b64 = Base64.getEncoder().encodeToString(chunk);
                Map<String, Object> ev = new HashMap<>();
                ev.put("idx", idx);
                ev.put("seq", s);
                ev.put("audioBase64", b64);
                if (s == 0) {
                    ev.put("text", sentence);
                    ev.put("chunkStart", true);
                    // 告知前端音频格式：raw PCM int16 LE 单声道
                    // 采样率（实测，curl /tts media_type=wav 后看 wav header）：
                    //   v1/v2 = 32000Hz；v2Pro/v2ProPlus/v3/v4 = 48000Hz
                    // 之前误填 32000 导致前端按 32k 拼 AudioBuffer → 1.5x 慢放 + 降调 → "完全不像黍"
                    // 当前用 v2Pro 推理，正确值是 48000
                    ev.put("format", "pcm_s16le");
                    ev.put("sampleRate", 48000);
                    ev.put("channels", 1);
                }
                sink.tryEmitNext(sse("tts", jsonObj(ev)));
            });
            long cost = System.currentTimeMillis() - t0;
            if (n <= 0) {
                Map<String, Object> ev = new HashMap<>();
                ev.put("idx", idx);
                ev.put("text", sentence);
                ev.put("error", "tts_failed");
                sink.tryEmitNext(sse("tts", jsonObj(ev)));
                return;
            }
            // 尾包：通知前端该句结束
            Map<String, Object> tail = new HashMap<>();
            tail.put("idx", idx);
            tail.put("seq", seq.get());
            tail.put("chunkEnd", true);
            tail.put("bytes", n);
            tail.put("costMs", cost);
            tail.put("ttfbMs", firstChunkCost[0]);
            sink.tryEmitNext(sse("tts", jsonObj(tail)));
            log.info("TTS sentence#{} done: bytes={}, chunks={}, ttfb={}ms, total={}ms",
                    idx, n, seq.get(), firstChunkCost[0], cost);
        } catch (Exception e) {
            log.error("emitTts 失败 idx={} sentence={}", idx, sentence, e);
            Map<String, Object> ev = new HashMap<>();
            ev.put("idx", idx);
            ev.put("text", sentence);
            ev.put("error", e.getMessage() == null ? "unknown" : e.getMessage());
            sink.tryEmitNext(sse("tts", jsonObj(ev)));
        }
    }

    private static String abbr(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
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