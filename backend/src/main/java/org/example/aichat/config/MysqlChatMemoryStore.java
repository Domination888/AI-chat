package org.example.aichat.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.dto.History;
import org.example.aichat.mapper.HistoryMapper;
import org.example.aichat.util.EmotionTagNormalizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 MySQL history 表的 ChatMemoryStore 实现（追加模式）。
 * <p>
 * - getMessages：只加载最近 LOAD_RECENT 条消息，供 ChatMemory 作为上下文窗口
 * - updateMessages：只追加最后一条新消息到 history 表，绝不删除历史记录
 * - history 表保持完整的对话日志，token_count 不会被重算
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlChatMemoryStore implements ChatMemoryStore {

    private final HistoryMapper historyMapper;

    /** 初始化加载的最近消息条数（与 ChatMemory 的 maxMessages 保持一致或略大） */
    private static final int LOAD_RECENT = 20;

    /**
     * 从 history 表加载最近 LOAD_RECENT 条消息。
     * ChatMemory 构造时自动调用此方法。
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String conversationId = (String) memoryId;
        List<History> historyList = historyMapper.findRecentByConversationId(conversationId, LOAD_RECENT);
        List<ChatMessage> messages = new ArrayList<>();
        for (History h : historyList) {
            ChatMessage msg = toChatMessage(h);
            if (msg != null) {
                messages.add(msg);
            }
        }
        log.debug("加载会话 {} 的最近 {} 条历史消息，实际加载 {} 条",
                conversationId, LOAD_RECENT, messages.size());
        return messages;
    }

    /**
     * 追加新消息到 history 表。
     * ChatMemory 每次 add() 后会调用此方法，传入当前窗口内的完整消息列表。
     * 这里只取最后一条（即刚刚 add 进来的新消息）插入数据库，不清空也不重写。
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return;

        String conversationId = (String) memoryId;
        ChatMessage lastMsg = messages.get(messages.size() - 1);
        History h = toHistory(conversationId, lastMsg);
        if (h != null) {
            historyMapper.insert(h);
            log.debug("追加消息到会话 {}: sender={}, tokenCount={}",
                    conversationId, h.getSender(), h.getTokenCount());
        }
    }

    /**
     * 清空指定会话的所有历史消息（仅在显式调用 chatMemory.clear() 时触发）。
     */
    @Override
    public void deleteMessages(Object memoryId) {
        historyMapper.deleteByConversationId((String) memoryId);
        log.info("清空会话 {} 的所有历史消息", memoryId);
    }

    // ==================== 转换方法 ====================

    private ChatMessage toChatMessage(History history) {
        String content = history.getContent();
        // 对 AI 消息规范化情绪标签：修复数据库中已入库的越界标签（如 <温和>）
        if ("assistant".equals(history.getSender()) && content != null) {
            String normalized = EmotionTagNormalizer.normalize(content);
            if (!normalized.equals(content)) {
                log.info("历史消息情绪标签规范化: conv={}, 原始含越界标签已修复", history.getConversationId());
            }
            content = normalized;
        }
        return switch (history.getSender()) {
            case "user" -> UserMessage.from(content);
            case "assistant" -> AiMessage.from(content);
            default -> null;
        };
    }

    private History toHistory(String conversationId, ChatMessage message) {
        History h = new History();
        h.setConversationId(conversationId);

        if (message instanceof UserMessage userMsg) {
            h.setSender("user");
            if (userMsg.hasSingleText()) {
                h.setContent(userMsg.singleText());
            } else {
                StringBuilder sb = new StringBuilder();
                if (userMsg.contents() != null) {
                    for (dev.langchain4j.data.message.Content c : userMsg.contents()) {
                        if (c instanceof dev.langchain4j.data.message.TextContent) {
                            sb.append(((dev.langchain4j.data.message.TextContent) c).text()).append("\n");
                        }
                    }
                }
                String content = sb.toString().trim();
                h.setContent(content.isEmpty() ? "[图片内容]" : content);
            }
        } else if (message instanceof AiMessage aiMsg) {
            h.setSender("assistant");
            h.setContent(aiMsg.text());
        } else {
            return null; // 不存储 SystemMessage
        }

        h.setTokenCount(h.getContent() != null ? h.getContent().length() / 2 : 0);
        return h;
    }
}
