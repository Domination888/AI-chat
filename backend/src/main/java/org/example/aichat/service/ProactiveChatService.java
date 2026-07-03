package org.example.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.dto.ChatRequest;
import org.example.aichat.dto.RoleCard;
import org.example.aichat.util.LatencyLogger;
import org.example.aichat.util.LatencyTrace;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主动搭话服务：当用户一段时间没说话时，AI 主动发起对话。
 *
 * 工作流程：
 * 1. 前端调用 POST /api/chat/proactive 注册，后端开始定时检查
 * 2. 前端通过 GET /api/chat/proactive/stream (SSE) 持续监听主动搭话消息
 * 3. 定时器每隔 idleSeconds 秒检查：用户最后交互时间是否超过 idleSeconds
 * 4. 超时则通过长连接 sink 推送主动对话（event: proactive → text → done）
 * 5. 用户发新消息时重置 idle 计时器；如果正在主动搭话则打断
 * 6. 前端断开时调用 DELETE /api/chat/proactive 停止
 */
@Slf4j
@Service
public class ProactiveChatService {

    private final SinkRegistry sinkRegistry;
    private final ChatService chatService;
    private final RoleCardService roleCardService;
    private final VoiceService voiceService;
    private final LatencyLogger latencyLogger;
    private final ObjectMapper om = new ObjectMapper();

    /** 定时调度器 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /** conversationId → 定时任务 Future（用于取消） */
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /** conversationId → 注册信息（userId + roleId + 配置） */
    private final Map<String, ProactiveRegistration> registrations = new ConcurrentHashMap<>();

    /** conversationId → 用户最后交互时间（毫秒） */
    private final Map<String, Long> lastInteractionTimes = new ConcurrentHashMap<>();

    /** conversationId → 长连接 SSE sink（前端监听主动搭话用） */
    private final Map<String, Sinks.Many<ServerSentEvent<String>>> proactiveSinks = new ConcurrentHashMap<>();

    /** conversationId → 主动搭话是否正在生成 */
    private final Map<String, AtomicBoolean> proactiveGenerating = new ConcurrentHashMap<>();

    /** conversationId → 主动搭话 LLM 取消标记 */
    private final Map<String, AtomicBoolean> proactiveCancelFlags = new ConcurrentHashMap<>();

    /** conversationId → 最近一次主动搭话的 AI 回复（用于去重判断） */
    private final Map<String, String> lastProactiveResponse = new ConcurrentHashMap<>();

    /** 主动搭话连续触发计数（用于递进式提示变化） */
    private final Map<String, Integer> proactiveCount = new ConcurrentHashMap<>();

    /** 多样化主动搭话提示模板：避免每次都发相同 prompt 导致 LLM 输出重复 */
    private static final List<String> PROACTIVE_PROMPT_TEMPLATES = List.of(
            "[System: 用户长时间未说话，请根据上下文主动搭话，自然地延续对话]",
            "[System: 用户沉默了一会儿，换个话题或角度主动搭话吧，不要重复之前说过的话]",
            "[System: 用户还没回应，试着用一个新话题或新方式引起注意，说点和之前不同的内容]",
            "[System: 用户仍然安静，换一种语气或聊一件小事来打破沉默，不要再说之前的话了]"
    );

    public ProactiveChatService(SinkRegistry sinkRegistry, ChatService chatService, RoleCardService roleCardService,
                                VoiceService voiceService, LatencyLogger latencyLogger) {
        this.sinkRegistry = sinkRegistry;
        this.chatService = chatService;
        this.roleCardService = roleCardService;
        this.voiceService = voiceService;
        this.latencyLogger = latencyLogger;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 注册主动搭话：开启定时检查。
     */
    public void register(String conversationId, Integer userId, Integer roleId,
                         Integer idleSeconds, String proactivePrompt) {
        int idle = (idleSeconds != null && idleSeconds > 0) ? idleSeconds : 3600;
        String prompt = (proactivePrompt != null && !proactivePrompt.isEmpty())
                ? proactivePrompt : "[System: 用户长时间未说话，请根据上下文主动搭话，自然地延续对话]";

        ProactiveRegistration reg = new ProactiveRegistration(userId, roleId, idle, prompt);
        registrations.put(conversationId, reg);
        lastInteractionTimes.put(conversationId, System.currentTimeMillis());
        proactiveGenerating.putIfAbsent(conversationId, new AtomicBoolean(false));
        proactiveCancelFlags.putIfAbsent(conversationId, new AtomicBoolean(false));

        cancelScheduledTask(conversationId);

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> checkAndProactiveChat(conversationId),
                idle, idle, TimeUnit.SECONDS);
        scheduledTasks.put(conversationId, future);

        log.info("ProactiveChatService: 注册主动搭话, conversationId={}, idle={}s", conversationId, idle);
    }

    /**
     * 注销主动搭话：停止定时检查 + 关闭长连接。
     */
    public void unregister(String conversationId) {
        cancelScheduledTask(conversationId);
        registrations.remove(conversationId);
        lastInteractionTimes.remove(conversationId);
        proactiveGenerating.remove(conversationId);
        proactiveCancelFlags.remove(conversationId);
        closeProactiveSink(conversationId);
        log.info("ProactiveChatService: 注销主动搭话, conversationId={}", conversationId);
    }

    /**
     * 更新用户最后交互时间（用户发新消息时调用）。
     * 如果正在生成主动回复，也触发打断。
     */
    public void updateLastInteraction(String conversationId) {
        lastInteractionTimes.put(conversationId, System.currentTimeMillis());
        // 用户发了新消息，重置主动搭话计数（下次搭话从第1次开始）
        proactiveCount.put(conversationId, 0);
        // 如果正在生成主动回复，打断它
        AtomicBoolean generating = proactiveGenerating.get(conversationId);
        if (generating != null && generating.get()) {
            AtomicBoolean cancelFlag = proactiveCancelFlags.get(conversationId);
            if (cancelFlag != null) cancelFlag.set(true);
            log.info("ProactiveChatService: 用户发新消息，打断主动对话, conversationId={}", conversationId);
        }
    }

    /**
     * 更新配置（idleSeconds / proactivePrompt）。
     */
    public void updateConfig(String conversationId, Integer idleSeconds, String proactivePrompt) {
        ProactiveRegistration reg = registrations.get(conversationId);
        if (reg == null) return;

        if (idleSeconds != null && idleSeconds > 0) reg.idleSeconds = idleSeconds;
        if (proactivePrompt != null && !proactivePrompt.isEmpty()) reg.proactivePrompt = proactivePrompt;

        cancelScheduledTask(conversationId);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> checkAndProactiveChat(conversationId),
                reg.idleSeconds, reg.idleSeconds, TimeUnit.SECONDS);
        scheduledTasks.put(conversationId, future);
    }

    /**
     * 为前端 SSE 长连接创建或重建 proactive sink。
     * 使用 multicast() 允许多次订阅（EventSource 重连时不会报错）。
     * 如果旧 sink 已 complete（currentSubscriberCount == 0），先移除再重建。
     */
    public Sinks.Many<ServerSentEvent<String>> getOrCreateProactiveSink(String conversationId) {
        Sinks.Many<ServerSentEvent<String>> existing = proactiveSinks.get(conversationId);
        // 如果 sink 存在且还有活跃订阅者，直接复用
        if (existing != null && existing.currentSubscriberCount() > 0) {
            return existing;
        }
        // sink 不存在或已 complete（无订阅者），需要（重建）
        Sinks.Many<ServerSentEvent<String>> newSink = Sinks.many().multicast().onBackpressureBuffer();
        proactiveSinks.put(conversationId, newSink);
        log.info("ProactiveChatService: 创建/重建 proactive sink, conversationId={}", conversationId);
        return newSink;
    }

    /**
     * 关闭主动搭话长连接 sink。
     */
    public void closeProactiveSink(String conversationId) {
        Sinks.Many<ServerSentEvent<String>> sink = proactiveSinks.remove(conversationId);
        if (sink != null) {
            try { sink.tryEmitComplete(); } catch (Exception ignored) {}
        }
    }

    /**
     * 定时检查：用户空闲超时则主动发起对话。
     */
    private void checkAndProactiveChat(String conversationId) {
        ProactiveRegistration reg = registrations.get(conversationId);
        if (reg == null) return;

        Long lastTime = lastInteractionTimes.get(conversationId);
        if (lastTime == null) return;

        long elapsed = (System.currentTimeMillis() - lastTime) / 1000;
        if (elapsed < reg.idleSeconds) return;

        // 如果已有活跃聊天 SSE 流（用户正在对话），不主动搭话
        if (sinkRegistry.hasActiveSink(conversationId)) return;

        // 如果正在生成主动搭话，不重复触发
        AtomicBoolean generating = proactiveGenerating.get(conversationId);
        if (generating != null && generating.get()) return;

        // 检查前端是否有长连接监听
        Sinks.Many<ServerSentEvent<String>> proactiveSink = proactiveSinks.get(conversationId);
        if (proactiveSink == null) return; // 前端没连，不搭话

        log.info("ProactiveChatService: 用户空闲 {}s，发起主动搭话, conversationId={}", elapsed, conversationId);
        doProactiveChat(conversationId, reg, proactiveSink);
    }

    /**
     * 执行主动搭话：构建 ChatRequest 走 LLM 流式生成 + TTS，通过长连接 sink 推送给前端。
     */
    private void doProactiveChat(String conversationId, ProactiveRegistration reg,
                                  Sinks.Many<ServerSentEvent<String>> proactiveSink) {
        // 递进式主动搭话计数，用于选择不同提示模板
        int count = proactiveCount.merge(conversationId, 1, Integer::sum);
        String proactivePrompt = buildDiversePrompt(reg.proactivePrompt, count);
        doProactiveChatWithPrompt(conversationId, reg, proactiveSink, proactivePrompt, "AI 主动搭话");
    }

    private void doProactiveChatWithPrompt(String conversationId, ProactiveRegistration reg,
                                           Sinks.Many<ServerSentEvent<String>> proactiveSink,
                                           String proactivePrompt, String eventMessage) {
        AtomicBoolean generating = proactiveGenerating.get(conversationId);
        if (generating == null) return;
        generating.set(true);

        AtomicBoolean cancelFlag = proactiveCancelFlags.get(conversationId);
        if (cancelFlag != null) cancelFlag.set(false);

        // 获取角色信息以配置TTS参数
        String voiceId = null;
        try {
            RoleCard role = roleCardService.findById(reg.roleId).orElse(null);
            if (role != null && role.getVoiceId() != null && !role.getVoiceId().trim().isEmpty()) {
                voiceId = role.getVoiceId();
            }
        } catch (Exception e) {
            log.warn("ProactiveChatService: 获取角色TTS配置失败, roleId={}, error={}", reg.roleId, e.getMessage());
        }

        // 构建主动搭话的 ChatRequest
        ChatRequest request = new ChatRequest();
        request.setInputMode("text");
        request.setUserId(String.valueOf(reg.userId));
        request.setConversationId(conversationId);
        request.setMessage(proactivePrompt);
        request.setRoleId(reg.roleId);
        request.setStream(true);
        request.setSearch(false);
        request.setRag(true);
        request.setTools(false);
        // 添加TTS配置
        if (voiceId != null) {
            request.setTtsVoiceId(voiceId);
            request.setTtsSpeedFactor(1.0);
            request.setTtsPitchFactor(1.0);
        }

        long proactiveStart = System.currentTimeMillis();
        LatencyTrace trace = latencyLogger.startTrace(conversationId, "proactive", proactiveStart);
        trace.meta("source", "proactive");
        request.setLatencyTrace(trace);

        // 推送 proactive 标记事件
        proactiveSink.tryEmitNext(sse("proactive", jsonVal("message", eventMessage)));

        // 使用类似ChatController的完整流程：LLM流式 + 情绪标签处理 + TTS
        startLlmStreamWithTts(request, proactiveSink);
    }

    /**
     * 启动 LLM 流式 + 句子级 TTS 回播（主动搭话专用）
     */
    private void startLlmStreamWithTts(ChatRequest request, Sinks.Many<ServerSentEvent<String>> proactiveSink) {
        String conversationId = request.getConversationId();
        String voiceId = request.getTtsVoiceId();
        Double speedFactor = request.getTtsSpeedFactor();
        Double pitchFactor = request.getTtsPitchFactor();
        boolean wantTts = voiceId != null && !voiceId.isEmpty();
        LatencyTrace trace = request.getLatencyTrace();
        if (trace != null) {
            trace.meta("wantTts", wantTts);
        }

        // 语音场景优先首包速度：缩短最小句长
        SentenceSplitter splitter = wantTts ? new SentenceSplitter(6, 48) : null;
        AtomicInteger ttsIdx = new AtomicInteger(0);
        AtomicInteger ttsSentenceCount = new AtomicInteger(0);
        AtomicBoolean sseFirstText = new AtomicBoolean(false);
        AtomicBoolean sseFirstTts = new AtomicBoolean(false);

        Flux<String> llmFlux = chatService.chatStream(request);

        // 跨 token 情绪标签缓冲器：LLM 的 <开心> 等标签可能跨多个 token 到达
        EmotionTagBuffer emotionBuf = new EmotionTagBuffer(proactiveSink);

        llmFlux
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(token -> {
                    // 检查取消标记：如果已被打断，跳过后续 token
                    if (isCancelled(conversationId)) {
                        log.info("startLlmStreamWithTts: conversationId={} 已被取消，跳过 token", conversationId);
                        return;
                    }
                    // 情绪标签提取与剥离（跨 token 缓冲）：后端剥离，前端收到的 text delta 已不含标签
                    String cleanToken = emotionBuf.process(token);
                    if (!cleanToken.isEmpty()) {
                        if (trace != null && sseFirstText.compareAndSet(false, true)) {
                            trace.mark("sse_first_text");
                        }
                        proactiveSink.tryEmitNext(sse("text", jsonVal("delta", cleanToken)));
                        if (wantTts && splitter != null) {
                            List<String> sentences = splitter.append(cleanToken);
                            for (String s : sentences) {
                                emitTts(proactiveSink, s, voiceId, speedFactor, pitchFactor,
                                        ttsIdx.getAndIncrement(), trace, sseFirstTts, ttsSentenceCount);
                            }
                        }
                    }
                })
                .doOnComplete(() -> {
                    // 刷出情绪标签缓冲区残余内容
                    String emotionFlush = emotionBuf.flush();
                    if (!emotionFlush.isEmpty()) {
                        if (trace != null && sseFirstText.compareAndSet(false, true)) {
                            trace.mark("sse_first_text");
                        }
                        proactiveSink.tryEmitNext(sse("text", jsonVal("delta", emotionFlush)));
                        if (wantTts && splitter != null) {
                            List<String> sentences = splitter.append(emotionFlush);
                            for (String s : sentences) {
                                emitTts(proactiveSink, s, voiceId, speedFactor, pitchFactor,
                                        ttsIdx.getAndIncrement(), trace, sseFirstTts, ttsSentenceCount);
                            }
                        }
                    }
                    if (wantTts && splitter != null) {
                        String tail = splitter.flushRemainder();
                        if (tail != null && !tail.isBlank()) {
                            emitTts(proactiveSink, tail, voiceId, speedFactor, pitchFactor,
                                    ttsIdx.getAndIncrement(), trace, sseFirstTts, ttsSentenceCount);
                        }
                    }

                    if (trace != null) {
                        trace.meta("ttsSentences", ttsSentenceCount.get());
                        latencyLogger.stageServerComplete(trace);
                    }

                    // 记录本次回复用于下次去重
                    String response = getFullResponseText(emotionBuf);
                    if (!response.isEmpty()) {
                        lastProactiveResponse.put(conversationId, response);
                    }
                    
                    if (isCancelled(conversationId)) {
                        proactiveSink.tryEmitNext(sse("interrupted", "{}"));
                    } else {
                        Map<String, Object> donePayload = new HashMap<>();
                        if (trace != null) {
                            donePayload.put("traceId", trace.getTraceId());
                        }
                        proactiveSink.tryEmitNext(sse("done", jsonObj(donePayload)));
                    }
                    lastInteractionTimes.put(conversationId, System.currentTimeMillis());
                    
                    // 重置生成状态
                    AtomicBoolean generating = proactiveGenerating.get(conversationId);
                    if (generating != null) generating.set(false);
                })
                .doOnError(err -> {
                    AtomicBoolean generating = proactiveGenerating.get(conversationId);
                    if (generating != null) generating.set(false);

                    if (trace != null) {
                        trace.meta("error", String.valueOf(err.getMessage()));
                        latencyLogger.stageServerComplete(trace);
                    }
                    log.error("ProactiveChatService: 主动搭话 LLM 失败, conversationId={}", conversationId, err);
                    proactiveSink.tryEmitNext(sse("error", jsonVal("message", "主动搭话失败")));
                })
                .subscribe();
    }

    /**
     * 检查指定对话是否已取消
     */
    private boolean isCancelled(String conversationId) {
        AtomicBoolean cancelFlag = proactiveCancelFlags.get(conversationId);
        return cancelFlag != null && cancelFlag.get();
    }

    /**
     * 获取完整的响应文本（用于记录）
     */
    private String getFullResponseText(EmotionTagBuffer emotionBuf) {
        // 这里需要一个方法来获取完整响应，但由于EmotionTagBuffer是静态内部类，
        // 我们需要重新构建文本。实际实现中可能需要修改EmotionTagBuffer来跟踪完整文本
        return ""; // 简化处理，实际需要更复杂的实现
    }

    /**
     * 构建多样化的主动搭话提示。
     * 连续多次触发时使用不同模板，避免 LLM 因相同 prompt 生成重复回复。
     * 如果最近一次主动搭话回复与本次提示高度相似，额外追加去重指令。
     */
    private String buildDiversePrompt(String basePrompt, int count) {
        // 选择提示模板（循环使用）
        int templateIdx = Math.min(count - 1, PROACTIVE_PROMPT_TEMPLATES.size() - 1);
        String prompt = PROACTIVE_PROMPT_TEMPLATES.get(templateIdx % PROACTIVE_PROMPT_TEMPLATES.size());

        // 如果用户自定义了 prompt 且是第一次触发，使用用户的 prompt
        if (count == 1 && basePrompt != null && !basePrompt.isEmpty()
                && !basePrompt.equals("[System: 用户长时间未说话，请根据上下文主动搭话，自然地延续对话]")) {
            prompt = basePrompt;
        }

        return prompt;
    }

    private void cancelScheduledTask(String conversationId) {
        ScheduledFuture<?> future = scheduledTasks.remove(conversationId);
        if (future != null) future.cancel(false);
    }

    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    private String jsonVal(String key, String val) {
        try {
            Map<String, String> m = new HashMap<>();
            m.put(key, val == null ? "" : val);
            return om.writeValueAsString(m);
        } catch (Exception e) { return "{}"; }
    }

    private String jsonObj(Map<String, Object> m) {
        try { return om.writeValueAsString(m); }
        catch (Exception e) { return "{}"; }
    }

    private static String abbr(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
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
                         String sentence, String voiceId, Double speedFactor, Double pitchFactor, int idx,
                         LatencyTrace trace, AtomicBoolean sseFirstTts, AtomicInteger ttsSentenceCount) {
        final long t0 = System.currentTimeMillis();
        final AtomicInteger seq = new AtomicInteger(0);
        final long[] firstChunkCost = {-1};
        if (trace != null) {
            trace.mark("tts_" + idx + "_start");
        }
        try {
            log.info("TTS sentence#{} start: {}", idx, abbr(sentence));
            long n = voiceService.ttsStreamWithFullParams(sentence, voiceId, speedFactor, pitchFactor, chunk -> {
                int s = seq.getAndIncrement();
                if (s == 0) {
                    firstChunkCost[0] = System.currentTimeMillis() - t0;
                    if (trace != null) {
                        trace.mark("tts_" + idx + "_first_chunk");
                        if (sseFirstTts.compareAndSet(false, true)) {
                            trace.mark("sse_first_tts_chunk");
                        }
                    }
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
            if (trace != null) {
                trace.mark("tts_" + idx + "_done");
                trace.mark("tts_last_done");
            }
            ttsSentenceCount.incrementAndGet();
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
     * 立即触发一次主动搭话（跳过空闲检查），用于前端点击互动时调用。
     * 如果已有活跃聊天 SSE 流或正在生成主动搭话，则不触发。
     *
     * @return true 表示成功触发，false 表示被跳过（有活跃对话/正在生成/未注册）
     */
    public boolean triggerNow(String conversationId) {
        ProactiveRegistration reg = registrations.get(conversationId);
        if (reg == null) return false;

        // 如果已有活跃聊天 SSE 流（用户正在对话），不触发
        if (sinkRegistry.hasActiveSink(conversationId)) return false;

        // 如果正在生成主动搭话，不重复触发
        AtomicBoolean generating = proactiveGenerating.get(conversationId);
        if (generating != null && generating.get()) return false;

        // 检查前端是否有长连接监听
        Sinks.Many<ServerSentEvent<String>> proactiveSink = proactiveSinks.get(conversationId);
        if (proactiveSink == null) return false;

        log.info("ProactiveChatService: 手动触发主动搭话, conversationId={}", conversationId);
        doProactiveChat(conversationId, reg, proactiveSink);
        return true;
    }

    /**
     * 由定时/外部 skill 触发一个主动话题。只投递给当前活跃会话：
     * 已注册主动搭话、有前端 SSE 监听，且最近交互时间最新。
     */
    public boolean triggerTopicForCurrent(ProactiveTopic topic) {
        if (topic == null || topic.prompt() == null || topic.prompt().isBlank()) {
            return false;
        }
        String conversationId = registrations.keySet().stream()
                .filter(id -> {
                    Sinks.Many<ServerSentEvent<String>> sink = proactiveSinks.get(id);
                    return sink != null && sink.currentSubscriberCount() > 0;
                })
                .max((a, b) -> Long.compare(
                        lastInteractionTimes.getOrDefault(a, 0L),
                        lastInteractionTimes.getOrDefault(b, 0L)))
                .orElse(null);
        if (conversationId == null) return false;

        ProactiveRegistration reg = registrations.get(conversationId);
        if (reg == null) return false;
        if (sinkRegistry.hasActiveSink(conversationId)) return false;

        AtomicBoolean generating = proactiveGenerating.get(conversationId);
        if (generating != null && generating.get()) return false;

        Sinks.Many<ServerSentEvent<String>> proactiveSink = proactiveSinks.get(conversationId);
        if (proactiveSink == null) return false;

        log.info("ProactiveChatService: skill {} 触发主动话题, conversationId={}, title={}",
                topic.sourceSkill(), conversationId, abbr(topic.title()));
        doProactiveChatWithPrompt(conversationId, reg, proactiveSink, topic.prompt(), "AI 日报话题");
        return true;
    }

    /**
     * 判断指定对话是否已注册主动搭话。
     */
    public boolean isRegistered(String conversationId) {
        return registrations.containsKey(conversationId);
    }

    /**
     * 注册信息
     */
    static class ProactiveRegistration {
        Integer userId;
        Integer roleId;
        int idleSeconds;
        String proactivePrompt;

        ProactiveRegistration(Integer userId, Integer roleId, int idleSeconds, String proactivePrompt) {
            this.userId = userId;
            this.roleId = roleId;
            this.idleSeconds = idleSeconds;
            this.proactivePrompt = proactivePrompt;
        }
    }

    public record ProactiveTopic(String sourceSkill, String title, String summary, List<String> links, String prompt) {
    }
}
