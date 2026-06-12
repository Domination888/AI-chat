package org.example.aichat.util;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 单次对话请求的全链路延迟追踪（毫秒时间戳，相对 client_sent / request_received 计算 delta）。
 */
@Getter
public class LatencyTrace {

    private final String traceId;
    private final String conversationId;
    private final String inputMode;
    private final long serverStartMs;
    private final LinkedHashMap<String, Long> steps = new LinkedHashMap<>();
    private final Map<String, Object> meta = new LinkedHashMap<>();

    public LatencyTrace(String conversationId, String inputMode, Long clientSentAt) {
        this.traceId = UUID.randomUUID().toString().substring(0, 8);
        this.conversationId = conversationId;
        this.inputMode = inputMode == null ? "text" : inputMode;
        this.serverStartMs = System.currentTimeMillis();
        if (clientSentAt != null && clientSentAt > 0) {
            mark("client_sent", clientSentAt);
        }
        mark("request_received", serverStartMs);
    }

    public void mark(String step) {
        steps.put(step, System.currentTimeMillis());
    }

    public void mark(String step, long epochMs) {
        steps.put(step, epochMs);
    }

    public void meta(String key, Object value) {
        meta.put(key, value);
    }

    /** 相对锚点（优先 client_sent，否则 request_received）的毫秒偏移 */
    public long anchorMs() {
        return steps.getOrDefault("client_sent", steps.getOrDefault("request_received", serverStartMs));
    }

    public Map<String, Long> allDeltas() {
        long anchor = anchorMs();
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : steps.entrySet()) {
            out.put(e.getKey(), e.getValue() - anchor);
        }
        return out;
    }

    public Map<String, Long> spanDeltas() {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        Long prev = null;
        for (Map.Entry<String, Long> e : steps.entrySet()) {
            if (prev == null) {
                out.put(e.getKey(), 0L);
            } else {
                out.put(e.getKey(), e.getValue() - prev);
            }
            prev = e.getValue();
        }
        return out;
    }
}
