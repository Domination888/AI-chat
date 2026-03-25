package org.example.aichat.service.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.example.aichat.dto.History;
import org.example.aichat.dto.Memory;
import org.example.aichat.mapper.HistoryMapper;
import org.example.aichat.mapper.MemoryMapper;
import org.example.aichat.service.MemoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MemoryServiceImpl implements MemoryService {

    private final MemoryMapper memoryMapper;
    private final HistoryMapper historyMapper;
    private final ChatModel chatModel;  // 通过 LlmConfig Bean 注入

    private static final int MAX_TOKENS = 4000;
    private static final int KEEP_RECENT = 10; // 压缩后保留最近的消息条数

    @Override
    public Memory findByConversationId(String conversationId) {
        return memoryMapper.findByConversationId(conversationId);
    }

    @Override
    public void updateMemory(String conversationId, String summary, int tokenCount) {
        Memory existing = memoryMapper.findByConversationId(conversationId);
        if (existing == null) {
            Memory memory = new Memory();
            memory.setConversationId(conversationId);
            memory.setSummary(summary);
            memory.setTokenCount(tokenCount);
            memoryMapper.insert(memory);
        } else {
            existing.setSummary(summary);
            existing.setTokenCount(tokenCount);
            memoryMapper.update(existing);
        }
    }

    /**
     * 检查 history 表中的总 token 数，超出阈值时：
     * 1. 将已有记忆摘要 + 当前历史消息一起发给 LLM 生成新摘要
     * 2. 更新 memory 表
     * 3. 删除最早的 N 条 history，只保留最近的 KEEP_RECENT 条
     *    （下次 ChatMemory 加载时自动获取保留的消息）
     */
    @Override
    public void compressIfNeeded(String conversationId) {
        int totalTokens = historyMapper.sumTokenByConversationId(conversationId);
        if (totalTokens < MAX_TOKENS) return;

        List<History> historyList = historyMapper.findByConversationId(conversationId);

        // 如果消息数不足，无需压缩
        if (historyList.size() <= KEEP_RECENT) return;

        // 拼接已有摘要 + 待压缩的历史消息
        Memory existingMemory = memoryMapper.findByConversationId(conversationId);
        String existingSummary = (existingMemory != null) ? existingMemory.getSummary() : "";

        StringBuilder text = new StringBuilder();
        if (existingSummary != null && !existingSummary.isEmpty()) {
            text.append("已有的记忆摘要：\n").append(existingSummary).append("\n\n");
        }
        text.append("新的对话内容：\n");
        historyList.forEach(h ->
                text.append(h.getSender())
                        .append(": ")
                        .append(h.getContent())
                        .append("\n")
        );

        // 调用 LLM 生成摘要
        String summary = summarize(text.toString());
        updateMemory(conversationId, summary, summary.length() / 2);
    }

    private String summarize(String content) {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("请将以下对话总结为简洁的长期记忆摘要，保留关键信息和上下文："),
                UserMessage.from(content)
        );
        ChatResponse response = chatModel.chat(messages);
        return response.aiMessage().text();
    }
}