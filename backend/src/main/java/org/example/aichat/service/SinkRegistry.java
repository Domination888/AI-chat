package org.example.aichat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局 SSE Sink 注册表：按 conversationId 维护当前活跃的 SSE 流。
 * <p>
 * 用途：
 * 1. 打断机制 — 调用 interrupt(conversationId) 可立即终止该对话的 SSE 流
 * 2. 主动搭话 — ProactiveChatService 通过此注册表推送主动消息
 */
@Slf4j
@Service
public class SinkRegistry {

    /** conversationId → 当前活跃的 SSE Sink */
    private final Map<String, Sinks.Many<ServerSentEvent<String>>> activeSinks = new ConcurrentHashMap<>();

    /** conversationId → 取消标记（LLM 流式生成时检查此标记，true 则跳过输出） */
    private final Map<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    /**
     * 注册一个 sink。如果该 conversationId 已有活跃 sink，先执行打断。
     */
    public void register(String conversationId, Sinks.Many<ServerSentEvent<String>> sink) {
        Sinks.Many<ServerSentEvent<String>> old = activeSinks.put(conversationId, sink);
        if (old != null) {
            log.info("SinkRegistry: conversationId={} 已有活跃 sink，先关闭旧 sink", conversationId);
            try { old.tryEmitComplete(); } catch (Exception ignored) {}
        }
        cancelFlags.put(conversationId, false);
        log.info("SinkRegistry: 注册 sink, conversationId={}", conversationId);
    }

    /**
     * 注销 sink（流正常结束或出错时调用）。
     */
    public void unregister(String conversationId) {
        activeSinks.remove(conversationId);
        cancelFlags.remove(conversationId);
        log.info("SinkRegistry: 注销 sink, conversationId={}", conversationId);
    }

    /**
     * 打断指定对话的 SSE 流：关闭 sink + 设置取消标记。
     * @return true 表示成功打断（有活跃流），false 表示没有活跃流
     */
    public boolean interrupt(String conversationId) {
        cancelFlags.put(conversationId, true);
        Sinks.Many<ServerSentEvent<String>> sink = activeSinks.remove(conversationId);
        if (sink != null) {
            log.info("SinkRegistry: 打断 conversationId={}", conversationId);
            try { sink.tryEmitComplete(); } catch (Exception ignored) {}
            cancelFlags.remove(conversationId);
            return true;
        }
        // 没有 sink 但可能有正在进行的 LLM 调用（sink 还没注册或已流转到 LLM 层）
        log.info("SinkRegistry: 打断标记已设置, conversationId={}（无活跃 sink）", conversationId);
        return false;
    }

    /**
     * 检查指定对话是否已被取消。
     */
    public boolean isCancelled(String conversationId) {
        return cancelFlags.getOrDefault(conversationId, false);
    }

    /**
     * 获取指定对话的活跃 sink（用于主动搭话推送）。
     */
    public Sinks.Many<ServerSentEvent<String>> getSink(String conversationId) {
        return activeSinks.get(conversationId);
    }

    /**
     * 判断指定对话是否有活跃的 SSE 流。
     */
    public boolean hasActiveSink(String conversationId) {
        return activeSinks.containsKey(conversationId);
    }
}