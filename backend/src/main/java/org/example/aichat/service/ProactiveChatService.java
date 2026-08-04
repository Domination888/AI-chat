package org.example.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.dto.ChatRequest;
import org.example.aichat.dto.RoleCard;
import org.example.aichat.util.LatencyLogger;
import org.example.aichat.util.LatencyTrace;
import org.example.aichat.mapper.ProactiveCandidateMapper;
import org.example.aichat.search.SearchSource;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.concurrent.ExecutorService;
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
    private final ProactiveCandidateMapper proactiveCandidateMapper;
    private final ConversationTopicStateService topicStateService;
    private final ObjectProvider<ProactiveResearchService> proactiveResearchServiceProvider;
    private final ObjectMapper om = new ObjectMapper();

    /** 定时调度器 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ExecutorService decisionExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "proactive-decision-worker");
        thread.setDaemon(true);
        return thread;
    });

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

    /** conversationId → 是否正在判断话题状态/准备联网新话题 */
    private final Map<String, AtomicBoolean> proactiveDeciding = new ConcurrentHashMap<>();

    /** conversationId → 主动搭话 LLM 取消标记 */
    private final Map<String, AtomicBoolean> proactiveCancelFlags = new ConcurrentHashMap<>();

    /** 主动搭话连续触发计数（用于递进式提示变化） */
    private final Map<String, Integer> proactiveCount = new ConcurrentHashMap<>();

    public ProactiveChatService(SinkRegistry sinkRegistry, ChatService chatService, RoleCardService roleCardService,
                                VoiceService voiceService, LatencyLogger latencyLogger,
                                ProactiveCandidateMapper proactiveCandidateMapper,
                                ConversationTopicStateService topicStateService,
                                ObjectProvider<ProactiveResearchService> proactiveResearchServiceProvider) {
        this.sinkRegistry = sinkRegistry;
        this.chatService = chatService;
        this.roleCardService = roleCardService;
        this.voiceService = voiceService;
        this.latencyLogger = latencyLogger;
        this.proactiveCandidateMapper = proactiveCandidateMapper;
        this.topicStateService = topicStateService;
        this.proactiveResearchServiceProvider = proactiveResearchServiceProvider;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        decisionExecutor.shutdownNow();
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
        proactiveDeciding.putIfAbsent(conversationId, new AtomicBoolean(false));
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
        proactiveDeciding.remove(conversationId);
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
        return proactiveSinks.computeIfAbsent(conversationId, id -> {
            // autoCancel=false：EventSource 短暂断线后仍复用同一条事件总线。
            // 断线期间最多缓冲 2048 个事件（包含 TTS 分片），避免结果被发到废弃 sink。
            Sinks.Many<ServerSentEvent<String>> sink =
                    Sinks.many().multicast().onBackpressureBuffer(2048, false);
            log.info("ProactiveChatService: 创建持久 proactive sink, conversationId={}", id);
            return sink;
        });
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

        log.info("ProactiveChatService: 用户空闲 {}s，开始主动对话决策, conversationId={}", elapsed, conversationId);
        triggerProactiveDecision(conversationId, TriggerKind.TIMER);
    }

    /**
     * 定时与 Live2D 点击共用的主动对话决策入口。
     * 旧话题未结束时只续聊；旧话题结束后才允许从 Search-RAG 候选中开启新话题。
     */
    private boolean triggerProactiveDecision(String conversationId, TriggerKind triggerKind) {
        ProactiveRegistration reg = registrations.get(conversationId);
        if (reg == null) {
            log.info("主动对话决策跳过：会话未注册, conversationId={}", conversationId);
            return false;
        }
        if (sinkRegistry.hasActiveSink(conversationId)) {
            log.info("主动对话决策跳过：普通聊天仍在生成, conversationId={}", conversationId);
            return false;
        }

        AtomicBoolean generating = proactiveGenerating.get(conversationId);
        AtomicBoolean deciding = proactiveDeciding.get(conversationId);
        if (generating == null || deciding == null || generating.get() || !deciding.compareAndSet(false, true)) {
            log.info("主动对话决策跳过：已有主动任务, conversationId={}", conversationId);
            return false;
        }

        Sinks.Many<ServerSentEvent<String>> proactiveSink = proactiveSinks.get(conversationId);
        if (proactiveSink == null) {
            deciding.set(false);
            log.info("主动对话决策跳过：SSE 尚未建立, conversationId={}", conversationId);
            return false;
        }

        long interactionAtStart = lastInteractionTimes.getOrDefault(conversationId, 0L);
        decisionExecutor.execute(() -> {
            try {
                ProactiveResearchService research = proactiveResearchServiceProvider.getIfAvailable();
                if (triggerKind == TriggerKind.TIMER && research != null && research.isQuietHoursNow()) {
                    log.debug("主动对话处于静默时段, conversationId={}", conversationId);
                    return;
                }

                ConversationTopicStateService.TopicStateResult state = topicStateService.classify(conversationId);
                log.info("主动对话话题状态: conversationId={}, state={}, reason={}, trigger={}",
                        conversationId, state.state(), state.reason(), triggerKind.wireValue);
                if (!canStartAfterDecision(conversationId, interactionAtStart)) return;

                if (state.isOpen()) {
                    int count = proactiveCount.merge(conversationId, 1, Integer::sum);
                    String prompt = buildContinuationPrompt(reg.proactivePrompt, count);
                    doProactiveChatWithPrompt(conversationId, reg, proactiveSink, prompt,
                            "自然延续当前话题", null, ConversationMode.CONTINUATION, triggerKind);
                    return;
                }

                if (triggerKind == TriggerKind.TIMER && research != null
                        && !research.isResearchedTopicCooldownElapsed(reg.userId)) {
                    log.debug("联网主动话题仍在冷却中, conversationId={}", conversationId);
                    return;
                }
                emitDecisionStatus(proactiveSink, "researching", triggerKind);
                log.info("主动对话开始准备联网新话题, conversationId={}, userId={}", conversationId, reg.userId);
                if (research == null) {
                    emitDecisionStatus(proactiveSink, "search_unavailable", triggerKind);
                    return;
                }
                ProactiveResearchService.PreparedTopic prepared = research.prepareTopicNow(reg.userId).orElse(null);
                if (prepared == null || !canStartAfterDecision(conversationId, interactionAtStart)) {
                    log.info("主动对话未找到可靠联网话题, conversationId={}, userId={}", conversationId, reg.userId);
                    emitDecisionStatus(proactiveSink, "no_reliable_topic", triggerKind);
                    return;
                }
                log.info("主动对话联网话题已就绪, conversationId={}, candidateId={}, title={}",
                        conversationId, prepared.candidateId(), abbr(prepared.title()));
                ProactiveTopic topic = new ProactiveTopic(prepared.candidateId(), "search-rag",
                        prepared.title(), "", prepared.sources(), prepared.reason(), prepared.prompt());
                boolean started = doProactiveChatWithPrompt(conversationId, reg, proactiveSink, topic.prompt(),
                        "联网发现了一个新话题", topic, ConversationMode.RESEARCHED_TOPIC, triggerKind);
                if (started && topic.candidateId() != null) {
                    proactiveCandidateMapper.markDelivered(topic.candidateId(), conversationId);
                }
            } catch (Exception e) {
                log.warn("主动对话决策失败, conversationId={}: {}", conversationId, e.getMessage());
                proactiveSink.tryEmitNext(sse("error", jsonVal("message", "主动对话决策失败")));
            } finally {
                deciding.set(false);
            }
        });
        return true;
    }

    private boolean canStartAfterDecision(String conversationId, long interactionAtStart) {
        if (sinkRegistry.hasActiveSink(conversationId)) return false;
        if (!registrations.containsKey(conversationId)) return false;
        if (lastInteractionTimes.getOrDefault(conversationId, 0L) != interactionAtStart) return false;
        AtomicBoolean generating = proactiveGenerating.get(conversationId);
        return generating != null && !generating.get();
    }

    private void emitDecisionStatus(Sinks.Many<ServerSentEvent<String>> sink, String phase, TriggerKind triggerKind) {
        sink.tryEmitNext(sse("proactive_status", jsonObj(Map.of(
                "phase", phase,
                "trigger", triggerKind.wireValue))));
    }

    private boolean doProactiveChatWithPrompt(String conversationId, ProactiveRegistration reg,
                                              Sinks.Many<ServerSentEvent<String>> proactiveSink,
                                              String proactivePrompt, String eventMessage,
                                              ProactiveTopic topic, ConversationMode mode,
                                              TriggerKind triggerKind) {
        AtomicBoolean generating = proactiveGenerating.get(conversationId);
        if (generating == null || !generating.compareAndSet(false, true)) return false;

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
        request.setInternalTrigger(true);
        if (topic != null && topic.candidateId() != null) {
            request.setAssistantCompleteListener(text -> proactiveCandidateMapper.saveResponse(
                    topic.candidateId(), org.example.aichat.util.EmotionTagNormalizer.stripAllTags(text)));
        }
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
        Map<String, Object> proactiveEvent = new HashMap<>();
        proactiveEvent.put("message", eventMessage);
        proactiveEvent.put("mode", mode.wireValue);
        proactiveEvent.put("trigger", triggerKind.wireValue);
        if (topic != null) {
            proactiveEvent.put("candidateId", topic.candidateId());
            proactiveEvent.put("title", topic.title());
            proactiveEvent.put("reason", topic.reason());
            proactiveEvent.put("sources", topic.sources() == null ? List.of() : topic.sources());
            proactiveEvent.put("topic", topic.title());
        }
        proactiveSink.tryEmitNext(sse("proactive", jsonObj(proactiveEvent)));

        // 使用类似ChatController的完整流程：LLM流式 + 情绪标签处理 + TTS
        startLlmStreamWithTts(request, proactiveSink);
        return true;
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

    private String buildContinuationPrompt(String basePrompt, int count) {
        StringBuilder prompt = new StringBuilder(
                "[System: 当前话题尚未结束。请查看最近对话，以角色口吻自然接着刚才的具体内容说1-3句话。" +
                        "不要换话题，不要引入新闻或声称刚刚联网搜索，也不要重复上一条回复。]");
        if (basePrompt != null && !basePrompt.isBlank()
                && !basePrompt.equals("[System: 用户长时间未说话，请根据上下文主动搭话，自然地延续对话]")) {
            prompt.append("\n主动对话偏好：").append(basePrompt);
        }
        if (count > 1) {
            prompt.append("\n这是连续第").append(count).append("次尝试续接，请换一种自然措辞。");
        }
        return prompt.toString();
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
                    // 与普通聊天保持一致：所有 raw PCM 都走前端同一条流式串行队列。
                    // 之前 pcm_f32le 会退回完整音频队列，和普通聊天的流式播放器各自播放，造成重叠。
                    if (!"wav".equals(fmt)) {
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
        log.info("ProactiveChatService: Live2D 触发主动对话决策, conversationId={}", conversationId);
        return triggerProactiveDecision(conversationId, TriggerKind.LIVE2D);
    }

    public List<ActiveTarget> activeTargets() {
        return registrations.entrySet().stream()
                .filter(entry -> {
                    Sinks.Many<ServerSentEvent<String>> sink = proactiveSinks.get(entry.getKey());
                    return sink != null && sink.currentSubscriberCount() > 0;
                })
                .map(entry -> new ActiveTarget(entry.getKey(), entry.getValue().userId, entry.getValue().roleId,
                        lastInteractionTimes.getOrDefault(entry.getKey(), 0L)))
                .toList();
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

    public record ActiveTarget(String conversationId, Integer userId, Integer roleId, long lastInteractionAt) {
    }

    public record ProactiveTopic(Long candidateId, String sourceSkill, String title, String summary,
                                 List<SearchSource> sources, String reason, String prompt) {
    }

    enum TriggerKind {
        TIMER("timer"),
        LIVE2D("live2d");

        private final String wireValue;

        TriggerKind(String wireValue) {
            this.wireValue = wireValue;
        }
    }

    enum ConversationMode {
        CONTINUATION("continuation"),
        RESEARCHED_TOPIC("researched_topic");

        private final String wireValue;

        ConversationMode(String wireValue) {
            this.wireValue = wireValue;
        }
    }
}
