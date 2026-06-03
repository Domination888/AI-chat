package org.example.aichat.util;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
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

/**
 * 将每次发给 LLM 的完整 prompt 写入 unified-logs/backend/prompt.log。
 * 只保留最近 5 次 prompt，单文件滚动覆盖。
 */
@Slf4j
public class PromptLogger {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String SEPARATOR = "\n" + "=".repeat(60) + "\n";
    private static final int MAX_ENTRIES = 5;

    private final Path logFile;

    public PromptLogger(Path baseLogDir) {
        try {
            Files.createDirectories(baseLogDir);
        } catch (IOException e) {
            log.warn("创建 prompt 日志目录失败: {}", baseLogDir, e);
        }
        this.logFile = baseLogDir.resolve("prompt.log");
    }

    /**
     * 记录一次完整的 prompt（只保留最近 5 次）
     *
     * @param conversationId 会话 ID
     * @param messages       发给 LLM 的消息列表
     */
    public synchronized void log(String conversationId, List<ChatMessage> messages) {
        try {
            // 1. 读取现有文件中的条目
            List<String> entries = readExistingEntries();

            // 2. 构建新条目
            String newEntry = buildEntry(conversationId, messages);
            entries.add(newEntry);

            // 3. 只保留最近 MAX_ENTRIES 条
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(0);
            }

            // 4. 写回文件
            StringBuilder content = new StringBuilder();
            content.append("# Prompt Log (最近 ").append(MAX_ENTRIES).append(" 次调用)\n");
            content.append("# 更新时间: ").append(LocalDateTime.now().format(TIME_FMT)).append("\n");
            content.append(SEPARATOR);
            for (String entry : entries) {
                content.append(entry);
                content.append(SEPARATOR);
            }

            Files.writeString(logFile, content.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Prompt 日志已写入: {}", logFile.getFileName());

        } catch (IOException e) {
            log.warn("写入 prompt 日志失败: {}", logFile, e);
        }
    }

    /**
     * 从现有文件中读取已保存的条目（按 SEPARATOR 分割）
     */
    private List<String> readExistingEntries() {
        List<String> entries = new ArrayList<>();
        if (!Files.exists(logFile) || !Files.isRegularFile(logFile)) {
            return entries;
        }
        try {
            String content = Files.readString(logFile, StandardCharsets.UTF_8);
            // 跳过文件头（前两行是头部信息 + 第一个分隔符）
            String[] parts = content.split(SEPARATOR.trim());
            for (String part : parts) {
                String trimmed = part.trim();
                // 跳过文件头部和空块
                if (trimmed.isEmpty() || trimmed.startsWith("# Prompt Log")) {
                    continue;
                }
                entries.add(trimmed + "\n");
            }
        } catch (IOException e) {
            log.warn("读取 prompt 日志失败，将重新创建: {}", logFile, e);
        }
        return entries;
    }

    /**
     * 构建单条 prompt 日志文本
     */
    private String buildEntry(String conversationId, List<ChatMessage> messages) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        StringBuilder sb = new StringBuilder();
        sb.append("Time: ").append(timestamp).append("\n");
        sb.append("ConversationId: ").append(conversationId).append("\n");
        sb.append("MessageCount: ").append(messages.size()).append("\n");
        sb.append("-".repeat(40)).append("\n\n");

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            String role = extractRole(msg);
            String content = extractContent(msg);
            sb.append("[").append(i).append("] ").append(role).append(":\n");
            sb.append(content).append("\n\n");
        }
        return sb.toString();
    }

    private String extractRole(ChatMessage msg) {
        if (msg instanceof SystemMessage) return "SYSTEM";
        if (msg instanceof UserMessage) return "USER";
        if (msg instanceof AiMessage) return "AI";
        return msg.type().name();
    }

    private String extractContent(ChatMessage msg) {
        if (msg instanceof SystemMessage sm) return sm.text();
        if (msg instanceof UserMessage um) {
            return um.singleText();
        }
        if (msg instanceof AiMessage am) {
            if (am.hasToolExecutionRequests()) {
                return am.text() + " [tool_calls: " + am.toolExecutionRequests() + "]";
            }
            return am.text();
        }
        return msg.toString();
    }
}