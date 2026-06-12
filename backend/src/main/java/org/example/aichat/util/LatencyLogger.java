package org.example.aichat.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 全链路延迟日志：写入 unified-logs/backend/latency.log，保留最近 N 条。
 * SSE 结束时立即落盘服务端步骤；前端上报后更新同 traceId 条目。
 */
@Slf4j
public class LatencyLogger {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String SEPARATOR = "\n" + "=".repeat(72) + "\n";
    private static final int MAX_ENTRIES = 30;
    private static final long CLIENT_WAIT_MS = 60_000;

    private final Path logFile;
    private final ConcurrentHashMap<String, PendingTrace> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "latency-log-flush");
        t.setDaemon(true);
        return t;
    });

    public LatencyLogger(Path baseLogDir) {
        try {
            Files.createDirectories(baseLogDir);
        } catch (IOException e) {
            log.warn("创建 latency 日志目录失败: {}", baseLogDir, e);
        }
        this.logFile = baseLogDir.resolve("latency.log").toAbsolutePath().normalize();
        log.info("Latency 日志路径: {}", this.logFile);
    }

    public LatencyTrace startTrace(String conversationId, String inputMode, Long clientSentAt) {
        return new LatencyTrace(conversationId, inputMode, clientSentAt);
    }

    /** SSE 结束：立即写入服务端步骤，并保留 trace 等待前端合并 */
    public void stageServerComplete(LatencyTrace trace) {
        if (trace == null) return;
        trace.mark("sse_done");
        pending.put(trace.getTraceId(), new PendingTrace(trace));
        writeEntry(trace, true);
        logSummary(trace, false);
        scheduler.schedule(() -> pending.remove(trace.getTraceId()), CLIENT_WAIT_MS, TimeUnit.MILLISECONDS);
    }

    /** 合并前端步骤（网络、首字、TTS 播放等）并更新同 traceId 条目 */
    public synchronized void mergeClientSteps(String traceId, Map<String, Long> clientSteps) {
        if (traceId == null || traceId.isBlank()) return;
        PendingTrace pendingTrace = pending.remove(traceId);
        if (pendingTrace == null) {
            log.debug("latency: traceId={} 无待合并 trace（可能已超时），跳过前端步骤", traceId);
            return;
        }
        LatencyTrace trace = pendingTrace.trace();
        if (clientSteps != null) {
            clientSteps.forEach((step, ts) -> {
                if (ts != null && ts > 0) {
                    trace.mark(step, ts);
                }
            });
        }
        writeEntry(trace, true);
        logSummary(trace, true);
    }

    private synchronized void writeEntry(LatencyTrace trace, boolean replaceSameTrace) {
        try {
            List<String> entries = readExistingEntries();
            if (replaceSameTrace) {
                String traceLine = "TraceId: " + trace.getTraceId();
                entries.removeIf(e -> e.contains(traceLine));
            }
            entries.add(buildEntry(trace));
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(0);
            }

            StringBuilder content = new StringBuilder();
            content.append("# Latency Log (最近 ").append(MAX_ENTRIES).append(" 次请求)\n");
            content.append("# 文件路径: ").append(logFile).append("\n");
            content.append("# 更新时间: ").append(LocalDateTime.now().format(TIME_FMT)).append("\n");
            content.append(SEPARATOR);
            for (String entry : entries) {
                content.append(entry);
                content.append(SEPARATOR);
            }

            Files.writeString(logFile, content.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Latency 日志已更新: traceId={}", trace.getTraceId());
        } catch (IOException e) {
            log.warn("写入 latency 日志失败: {} ({})", logFile, e.getMessage());
        }
    }

    private List<String> readExistingEntries() {
        List<String> entries = new ArrayList<>();
        if (!Files.exists(logFile) || !Files.isRegularFile(logFile)) {
            return entries;
        }
        try {
            String content = Files.readString(logFile, StandardCharsets.UTF_8);
            String[] parts = content.split(SEPARATOR.trim());
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("# Latency Log") || trimmed.startsWith("# 文件路径")
                        || trimmed.startsWith("# 更新时间")) {
                    continue;
                }
                entries.add(trimmed + "\n");
            }
        } catch (IOException e) {
            log.warn("读取 latency 日志失败: {}", logFile, e);
        }
        return entries;
    }

    private String buildEntry(LatencyTrace trace) {
        Map<String, Long> deltas = trace.allDeltas();
        Map<String, Long> spans = trace.spanDeltas();
        String timestamp = LocalDateTime.now().format(TIME_FMT);

        StringBuilder sb = new StringBuilder();
        sb.append("Time: ").append(timestamp).append("\n");
        sb.append("TraceId: ").append(trace.getTraceId()).append("\n");
        sb.append("ConversationId: ").append(trace.getConversationId()).append("\n");
        sb.append("InputMode: ").append(trace.getInputMode()).append("\n");
        for (Map.Entry<String, Object> m : trace.getMeta().entrySet()) {
            sb.append("Meta.").append(m.getKey()).append(": ").append(m.getValue()).append("\n");
        }
        sb.append("-".repeat(72)).append("\n");
        sb.append(String.format("%-28s %8s %8s%n", "Step", "Abs(ms)", "Span"));
        sb.append("-".repeat(72)).append("\n");
        for (String step : trace.getSteps().keySet()) {
            long abs = deltas.getOrDefault(step, -1L);
            long span = spans.getOrDefault(step, -1L);
            sb.append(String.format("%-28s %8d %8d%n", step, abs, span));
        }

        sb.append("-".repeat(72)).append("\n");
        appendSummaryLine(sb, "E2E (client→播放完)", trace, "client_sent", "client_last_tts_play");
        appendSummaryLine(sb, "E2E (client→SSE结束)", trace, "client_sent", "sse_done");
        appendSummaryLine(sb, "Server (收请求→SSE结束)", trace, "request_received", "sse_done");
        appendSummaryLine(sb, "LLM (发请求→首token)", trace, "llm_request", "llm_first_token");
        appendSummaryLine(sb, "LLM (发请求→完成)", trace, "llm_request", "llm_complete");
        appendSummaryLine(sb, "TTS (首句→末句生成)", trace, "tts_0_start", "tts_last_done");
        appendSummaryLine(sb, "Playback (首播→末播)", trace, "client_first_tts_play", "client_last_tts_play");
        return sb.toString();
    }

    private void appendSummaryLine(StringBuilder sb, String label, LatencyTrace trace, String from, String to) {
        Long tFrom = trace.getSteps().get(from);
        Long tTo = trace.getSteps().get(to);
        if (tFrom == null || tTo == null) return;
        sb.append(String.format("%s: %d ms%n", label, tTo - tFrom));
    }

    private void logSummary(LatencyTrace trace, boolean complete) {
        Map<String, Long> d = trace.allDeltas();
        log.info("[latency] traceId={} conv={} mode={} complete={} log={} client={} req={} llm_ttfb={} llm_done={} sse_done={} tts_last={} play_done={}",
                trace.getTraceId(),
                trace.getConversationId(),
                trace.getInputMode(),
                complete,
                logFile,
                d.getOrDefault("client_sent", -1L),
                d.getOrDefault("request_received", -1L),
                d.getOrDefault("llm_first_token", -1L),
                d.getOrDefault("llm_complete", -1L),
                d.getOrDefault("sse_done", -1L),
                d.getOrDefault("tts_last_done", -1L),
                d.getOrDefault("client_last_tts_play", -1L));
    }

    private record PendingTrace(LatencyTrace trace) {}
}
