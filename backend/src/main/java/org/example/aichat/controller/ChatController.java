package org.example.aichat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.dto.ChatRequest;
import org.example.aichat.service.ChatService;
import org.example.aichat.service.SentenceSplitter;
import org.example.aichat.service.SinkRegistry;
import org.example.aichat.service.ProactiveChatService;
import org.example.aichat.service.VoiceService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一聊天入口：文本输入和语音输入只是输入方式的选择，功能完全一致。
 * <p>
 * SSE 事件协议（语音/文本统一）：
 *   event: asr     data: {"text":"用户说的话"}          — 仅语音输入时，ASR 结果
 *   event: emotion  data: {"emotion":"开心"}           — LLM 输出中的情绪标签
 *   event: text    data: {"delta":"模型新token"}       — LLM 流式文本（已剥离情绪标签）
 *   event: tts     data: {"idx":N,"seq":S,...}          — 句子级 TTS 音频 chunk
 *   event: done    data: {}                             — 流结束
 *   event: error   data: {"message":"..."}              — 错误
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final VoiceService voiceService;
    private final SinkRegistry sinkRegistry;
    private final ProactiveChatService proactiveChatService;
    private final ObjectMapper om = new ObjectMapper();

    private static final int MAX_IMAGE_COUNT = 5;
    private static final int MAX_BASE64_LENGTH = 5 * 1024 * 1024; // 约 5MB

    /**
     * 统一流式聊天入口（SSE）
     * <p>
     * 无论是文本输入还是语音输入，都走这个接口：
     * - inputMode=text  → 直接用 message 走 LLM
     * - inputMode=audio → 先 ASR(audioBase64) 拿文本，再走 LLM
     * <p>
     * 都支持：联网搜索、RAG、MCP 工具、多模态（图片）、TTS 回播
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request) {
        validateRequest(request);

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        String conversationId = request.getConversationId();

        // 注册 sink 到全局注册表（支持打断 + 主动搭话）
        sinkRegistry.register(conversationId, sink);

        // 更新主动搭话的交互时间（用户发了新消息）
        proactiveChatService.updateLastInteraction(conversationId);

        boolean isAudio = "audio".equalsIgnoreCase(request.getInputMode());

        if (isAudio) {
            handleAudioInput(request, sink);
        } else {
            handleTextInput(request, sink);
        }

        return sink.asFlux()
                .doOnComplete(() -> sinkRegistry.unregister(conversationId))
                .doOnError(err -> sinkRegistry.unregister(conversationId))
                .doOnCancel(() -> sinkRegistry.unregister(conversationId));
    }

    /**
     * 打断指定对话的 SSE 流：前端用户新发消息时调用，取消上一轮 LLM 生成 + TTS 播放
     */
    @PostMapping("/interrupt")
    public Map<String, Object> interrupt(@RequestBody Map<String, String> body) {
        String conversationId = body.get("conversationId");
        if (conversationId == null || conversationId.isEmpty()) {
            return Map.of("success", false, "message", "conversationId 不能为空");
        }
        boolean interrupted = sinkRegistry.interrupt(conversationId);
        return Map.of("success", true, "interrupted", interrupted);
    }

    // ==========================================================
    // 主动搭话（Proactive Chat）接口
    // ==========================================================

    /**
     * 注册主动搭话：后端开始定时检查用户空闲。
     * 请求体: { conversationId, userId, roleId, idleSeconds?, proactivePrompt? }
     */
    @PostMapping("/proactive")
    public Map<String, Object> registerProactive(@RequestBody Map<String, Object> body) {
        String conversationId = (String) body.get("conversationId");
        if (conversationId == null || conversationId.isEmpty()) {
            return Map.of("success", false, "message", "conversationId 不能为空");
        }
        Integer userId = body.get("userId") != null ? Integer.parseInt(body.get("userId").toString()) : 0;
        Integer roleId = body.get("roleId") != null ? Integer.parseInt(body.get("roleId").toString()) : 1;
        Integer idleSeconds = body.get("idleSeconds") != null ? Integer.parseInt(body.get("idleSeconds").toString()) : null;
        String proactivePrompt = (String) body.get("proactivePrompt");

        proactiveChatService.register(conversationId, userId, roleId, idleSeconds, proactivePrompt);
        return Map.of("success", true);
    }

    /**
     * 注销主动搭话：停止定时检查。
     * 请求体: { conversationId }
     */
    @DeleteMapping("/proactive")
    public Map<String, Object> unregisterProactive(@RequestBody Map<String, String> body) {
        String conversationId = body.get("conversationId");
        if (conversationId == null || conversationId.isEmpty()) {
            return Map.of("success", false, "message", "conversationId 不能为空");
        }
        proactiveChatService.unregister(conversationId);
        return Map.of("success", true);
    }

    /**
     * 立即触发一次主动搭话（跳过空闲检查），用于前端点击互动等场景。
     * 请求体: { conversationId }
     */
    @PostMapping("/proactive/trigger")
    public Map<String, Object> triggerProactive(@RequestBody Map<String, String> body) {
        String conversationId = body.get("conversationId");
        if (conversationId == null || conversationId.isEmpty()) {
            return Map.of("success", false, "message", "conversationId 不能为空");
        }
        boolean triggered = proactiveChatService.triggerNow(conversationId);
        return Map.of("success", triggered, "triggered", triggered);
    }

    /**
     * 更新主动搭话配置。
     * 请求体: { conversationId, idleSeconds?, proactivePrompt? }
     */
    @PostMapping("/proactive/config")
    public Map<String, Object> updateProactiveConfig(@RequestBody Map<String, Object> body) {
        String conversationId = (String) body.get("conversationId");
        if (conversationId == null || conversationId.isEmpty()) {
            return Map.of("success", false, "message", "conversationId 不能为空");
        }
        Integer idleSeconds = body.get("idleSeconds") != null ? Integer.parseInt(body.get("idleSeconds").toString()) : null;
        String proactivePrompt = (String) body.get("proactivePrompt");
        proactiveChatService.updateConfig(conversationId, idleSeconds, proactivePrompt);
        return Map.of("success", true);
    }

    /**
     * 主动搭话 SSE 长连接：前端通过此端点持续监听主动搭话消息。
     * 事件格式: event: proactive / text / done / interrupted / error
     * 注意：使用 multicast sink，EventSource 重连时 getOrCreateProactiveSink 会自动重建。
     */
    @GetMapping(value = "/proactive/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> proactiveStream(@RequestParam String conversationId) {
        Sinks.Many<ServerSentEvent<String>> sink = proactiveChatService.getOrCreateProactiveSink(conversationId);
        return sink.asFlux()
                .doOnCancel(() -> {
                    log.info("proactiveStream: 前端断开, conversationId={}（sink 保留，重连时重建）", conversationId);
                });
    }

    /**
     * 文本输入：直接走 LLM 流式 + 可选 TTS 回播
     */
    private void handleTextInput(ChatRequest request, Sinks.Many<ServerSentEvent<String>> sink) {
        startLlmStreamWithTts(request, sink);
    }

    /**
     * 语音输入：先 ASR → 再走 LLM 流式 + TTS 回播
     */
    private void handleAudioInput(ChatRequest request, Sinks.Many<ServerSentEvent<String>> sink) {
        Schedulers.boundedElastic().schedule(() -> {
            try {
                // 1) ASR：把 base64 音频解码，调用 VoiceService
                byte[] audioBytes = Base64.getDecoder().decode(request.getAudioBase64());
                String ext = request.getAudioFormat() != null ? request.getAudioFormat() : "webm";
                MultipartFile audioFile = new ByteArrayMultipartFile(audioBytes, "file", "audio." + ext, "audio/webm");

                String userText = voiceService.asr(audioFile, request.getAsrHotwords(), request.getAsrLanguage());
                if (userText == null || userText.trim().isEmpty()) {
                    sink.tryEmitNext(sse("error", json("message", "无法识别音频内容")));
                    sink.tryEmitComplete();
                    return;
                }
                // 推送 ASR 结果给前端
                sink.tryEmitNext(sse("asr", json("text", userText)));

                // 2) 用 ASR 文本替换 message，走 LLM 流式 + TTS
                request.setMessage(userText);
                startLlmStreamWithTts(request, sink);
            } catch (Exception e) {
                log.error("语音输入 ASR 失败", e);
                sink.tryEmitNext(sse("error", json("message", "语音识别失败: " + e.getMessage())));
                sink.tryEmitComplete();
            }
        });
    }

    /**
     * 跨 token 情绪标签缓冲器。
     *
     * LLM 流式输出时，"<开心>" 可能跨多个 token 到达（如 "<" 一个 token，
     * "开心>" 一个 token）。直接对单个 token 做正则匹配会漏掉跨 token 标签。
     *
     * 本缓冲器将每个新 token 追加到 buffer 末尾，然后扫描 buffer：
     * - 合法标签（<开心> 等）→ 发送 emotion SSE 事件 + 剥离
     * - 越界标签（<温和> 等）→ 静默剥离（不发送 emotion 事件，也不漏给前端）
     * - 尾部可能是不完整标签前缀（如 "<" 或 "<开"）→ 保留在 buffer 中等待后续 token
     * - 尾部确定不是标签（如 "<x" 其中 x 不是任何标签前缀）→ 当普通文本输出
     *
     * 调用方式：每次收到一个 token 调用 process()，返回值是当前可安全输出的纯文本。
     * 流结束时调用 flush() 输出缓冲区中残余内容。
     */
    static class EmotionTagBuffer {
        private final StringBuilder buf = new StringBuilder();
        private final Sinks.Many<ServerSentEvent<String>> sink;

        /** 匹配所有 <xxx> 格式标签（含越界标签，如 <温和>） */
        private static final java.util.regex.Pattern ANY_TAG_PATTERN =
                org.example.aichat.util.EmotionTagNormalizer.ANY_TAG_PATTERN;

        EmotionTagBuffer(Sinks.Many<ServerSentEvent<String>> sink) {
            this.sink = sink;
        }

        /**
         * 处理一个新 token，返回可安全输出的纯文本（已剥离所有 <xxx> 标签）。
         */
        String process(String token) {
            buf.append(token);
            return drainBuffer();
        }

        /**
         * 流结束时调用，输出缓冲区残余内容（不会有新的 token 到来）。
         */
        String flush() {
            // 最后再尝试一次完整匹配
            String result = drainBuffer();
            if (buf.length() > 0) {
                // 残余内容不会构成完整标签了，直接输出
                result += buf.toString();
                buf.setLength(0);
            }
            return result;
        }

        /**
         * 扫描 buffer，提取完整标签，返回可输出的纯文本。
         * 不完整的标签前缀留在 buffer 尾部等待后续 token。
         */
        private String drainBuffer() {
            String text = buf.toString();
            java.util.regex.Matcher matcher = ANY_TAG_PATTERN.matcher(text);
            StringBuilder clean = new StringBuilder();
            int lastEnd = 0;
            while (matcher.find()) {
                String tagContent = matcher.group(1);
                // 合法标签 → 发送 emotion SSE 事件
                if (org.example.aichat.util.EmotionTagNormalizer.isValidTag(tagContent)) {
                    sink.tryEmitNext(sse("emotion", json("emotion", tagContent)));
                }
                // 越界标签 → 静默剥离（不发送事件，也不漏给前端）
                clean.append(text, lastEnd, matcher.start());
                lastEnd = matcher.end();
            }

            if (lastEnd == 0) {
                // 没有完整匹配，检查尾部是否有不完整标签前缀
                int splitPos = findIncompleteTagPrefix(text);
                if (splitPos < text.length()) {
                    // 前半段是安全文本，后半段可能是标签前缀
                    buf.setLength(0);
                    buf.append(text, splitPos, text.length());
                    return text.substring(0, splitPos);
                }
                // 整个文本都没有 < 开头，全部安全输出
                buf.setLength(0);
                return text;
            }

            // 有完整匹配：lastEnd 之后的尾部可能是不完整标签前缀
            String tail = text.substring(lastEnd);
            int splitPos = findIncompleteTagPrefix(tail);
            if (splitPos < tail.length()) {
                // 保留不完整标签前缀在 buffer 中
                buf.setLength(0);
                buf.append(tail, splitPos, tail.length());
                clean.append(tail, 0, splitPos);
            } else {
                // 尾部无标签前缀
                clean.append(tail);
                buf.setLength(0);
            }
            return clean.toString();
        }

        /**
         * 找到字符串中最后一个可能是标签前缀的 '<' 位置。
         * 任何 '<' 之后的内容都可能是标签（含越界标签），因此保守保留。
         * 仅当 '<' 之后的内容已经不可能构成任何标签（如 "< " 后跟空格或 ">" 紧跟）时才视为普通文本。
         *
         * @return 可以安全输出的文本截止位置（该位置之后的部分需保留在 buffer 中）
         */
        private int findIncompleteTagPrefix(String text) {
            int lastLt = text.lastIndexOf('<');
            if (lastLt < 0) return text.length();

            // '<' 之后的内容可能是任意标签（含越界标签），
            // 只要 '<' 后面还没有 '>' 闭括号，就需要保留在 buffer 中等待
            String afterLt = text.substring(lastLt + 1);
            // 如果已经包含 '>'，说明这不是标签（标签应该在 drainBuffer 里被完整匹配了）
            if (afterLt.contains(">")) {
                return text.length();
            }
            // 没有 '>' 的 '<xxx' 一律视为可能的不完整标签前缀，保守保留
            return lastLt;
        }

        private static String json(String key, String val) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(java.util.Map.of(key, val == null ? "" : val));
            } catch (Exception e) { return "{}"; }
        }

        private static ServerSentEvent<String> sse(String event, String data) {
            return ServerSentEvent.<String>builder()
                    .event(event)
                    .data(data)
                    .build();
        }
    }

    /**
     * 启动 LLM 流式 + 句子级 TTS 回播（语音/文本共用）
     */
    private void startLlmStreamWithTts(ChatRequest request, Sinks.Many<ServerSentEvent<String>> sink) {
        String conversationId = request.getConversationId();
        String voiceId = request.getTtsVoiceId();
        Double speedFactor = request.getTtsSpeedFactor();
        Double pitchFactor = request.getTtsPitchFactor();
        boolean wantTts = StringUtils.hasText(voiceId);

        // 语音场景优先首包速度：缩短最小句长
        SentenceSplitter splitter = wantTts ? new SentenceSplitter(6, 60) : null;
        AtomicInteger ttsIdx = new AtomicInteger(0);

        Flux<String> llmFlux = chatService.chatStream(request);

        // 跨 token 情绪标签缓冲器：LLM 的 <开心> 等标签可能跨多个 token 到达
        EmotionTagBuffer emotionBuf = new EmotionTagBuffer(sink);

        llmFlux
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(token -> {
                    // 检查取消标记：如果已被打断，跳过后续 token
                    if (sinkRegistry.isCancelled(conversationId)) {
                        log.info("startLlmStreamWithTts: conversationId={} 已被取消，跳过 token", conversationId);
                        return;
                    }
                    // 情绪标签提取与剥离（跨 token 缓冲）：后端剥离，前端收到的 text delta 已不含标签
                    String cleanToken = emotionBuf.process(token);
                    if (!cleanToken.isEmpty()) {
                        // 文本流：推给前端（纯文本，不含情绪标签）
                        sink.tryEmitNext(sse("text", json("delta", cleanToken)));
                        // 如果需要 TTS，攒句子（用纯文本）
                        if (wantTts && splitter != null) {
                            List<String> sentences = splitter.append(cleanToken);
                            for (String s : sentences) {
                                emitTts(sink, s, voiceId, speedFactor, pitchFactor, ttsIdx.getAndIncrement());
                            }
                        }
                    }
                })
                .doOnComplete(() -> {
                    // 刷出情绪标签缓冲区残余内容
                    String emotionFlush = emotionBuf.flush();
                    if (!emotionFlush.isEmpty()) {
                        sink.tryEmitNext(sse("text", json("delta", emotionFlush)));
                        if (wantTts && splitter != null) {
                            List<String> sentences = splitter.append(emotionFlush);
                            for (String s : sentences) {
                                emitTts(sink, s, voiceId, speedFactor, pitchFactor, ttsIdx.getAndIncrement());
                            }
                        }
                    }
                    if (wantTts && splitter != null) {
                        String tail = splitter.flushRemainder();
                        if (tail != null && !tail.isBlank()) {
                            emitTts(sink, tail, voiceId, speedFactor, pitchFactor, ttsIdx.getAndIncrement());
                        }
                    }
                    sink.tryEmitNext(sse("done", "{}"));
                    sink.tryEmitComplete();
                })
                .doOnError(err -> {
                    log.error("chatStream LLM 失败", err);
                    sink.tryEmitNext(sse("error", json("message", String.valueOf(err.getMessage()))));
                    sink.tryEmitComplete();
                })
                .subscribe();
    }

    /**
     * 拿到一整句话后调 TTS，chunk 到就立即推给前端 SSE（真·流式）。
     * 协议扩展：单句会产生多条 tts 事件
     *   - event: tts   data: {"idx":N,"text":"...","seq":0,"audioBase64":"<chunk>","chunkStart":true}
     *   - event: tts   data: {"idx":N,"seq":1,"audioBase64":"<chunk>"}
     *   - event: tts   data: {"idx":N,"seq":K,"audioBase64":"<chunk>","chunkEnd":true,"bytes":NNN,"costMs":XX}
     * 前端按 idx 归类、按 seq 顺序拼装 Blob 播放。
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
                    String fmt = voiceService.currentTtsFormat();
                    ev.put("format", fmt);
                    ev.put("sampleRate", voiceService.currentTtsSampleRate());
                    ev.put("channels", 1);
                    // MLX 流式 PCM：前端按 chunk 边收边播，不必等 chunkEnd
                    if ("pcm_s16le".equals(fmt)) {
                        ev.put("streamPlay", true);
                    }
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

    // ==========================================================
    // 参数校验
    // ==========================================================
    private void validateRequest(ChatRequest request) {
        if (request.getUserId() == null) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (!StringUtils.hasText(request.getConversationId())) {
            throw new IllegalArgumentException("conversationId不能为空");
        }

        boolean isAudio = "audio".equalsIgnoreCase(request.getInputMode());

        if (isAudio) {
            // 语音输入：audioBase64 必填
            if (!StringUtils.hasText(request.getAudioBase64())) {
                throw new IllegalArgumentException("语音输入时 audioBase64 不能为空");
            }
        } else {
            // 文本输入：message 或 images 至少有一个
            boolean hasText = StringUtils.hasText(request.getMessage());
            boolean hasImages = !CollectionUtils.isEmpty(request.getImages());
            if (!hasText && !hasImages) {
                throw new IllegalArgumentException("必须提供文本或图片");
            }
            if (hasImages) {
                validateImages(request.getImages());
            }
        }
    }

    private void validateImages(List<String> images) {
        if (images.size() > MAX_IMAGE_COUNT) {
            throw new IllegalArgumentException("最多支持 " + MAX_IMAGE_COUNT + " 张图片");
        }
        for (String base64 : images) {
            if (!StringUtils.hasText(base64)) {
                throw new IllegalArgumentException("图片内容不能为空");
            }
            if (base64.length() > MAX_BASE64_LENGTH) {
                throw new IllegalArgumentException("单张图片不能超过5MB");
            }
            if (!base64.startsWith("data:image")) {
                throw new IllegalArgumentException("图片必须为base64格式");
            }
        }
    }

    // ==========================================================
    // SSE / JSON 工具方法
    // ==========================================================
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

    // ==========================================================
    // 简单的 MultipartFile 实现（避免依赖 spring-test 的 MockMultipartFile）
    // ==========================================================
    private static class ByteArrayMultipartFile implements MultipartFile {
        private final byte[] content;
        private final String name;
        private final String originalFilename;
        private final String contentType;

        ByteArrayMultipartFile(byte[] content, String name, String originalFilename, String contentType) {
            this.content = content;
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content == null || content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws IOException { throw new UnsupportedOperationException(); }
    }
}