package org.example.aichat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.config.LlmModelFactory;
import org.example.aichat.dto.History;
import org.example.aichat.mapper.HistoryMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 判断当前会话是否还有一个自然待续的话题。
 *
 * 明确信号先走本地规则，模糊情况再交给本地 LLM 做二分类；LLM 不可用时保守地继续
 * 原话题，避免在用户仍等着回应时突然切换到联网新闻。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationTopicStateService {
    private static final int HISTORY_LIMIT = 8;
    private static final int MAX_MESSAGE_CHARS = 1200;
    private static final Pattern CONTINUE_SIGNAL = Pattern.compile(
            "(继续|接着|展开|详细(说|讲)?|然后呢|为什么|怎么办|说下去|再讲讲|具体一点|还有呢)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSED_SIGNAL = Pattern.compile(
            "(先这样|就这样|到这里|到此为止|改天再聊|回头再说|再见|拜拜|晚安|" +
                    "好好休息|先休息|睡觉吧|吃完了|结束了|完成了|搞定了|" +
                    "^(好|好的|好吧|行|明白了?|知道了?|懂了?|谢谢|感谢|收到|没事了)[啊呀哦呢吧嘛～~.!。！ ]*$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSISTANT_CLOSURE = Pattern.compile(
            "(先歇着|先休息|好好休息|睡一会|睡吧|晚安|明天(再|醒)|改天再|回头再|" +
                    "告一段落|到这里|已经结束|已经完成|回归宁静)",
            Pattern.CASE_INSENSITIVE);

    private final HistoryMapper historyMapper;
    private final LlmModelFactory llmModelFactory;
    private final ObjectMapper objectMapper;

    public TopicStateResult classify(String conversationId) {
        List<History> history = historyMapper.findRecentByConversationId(conversationId, HISTORY_LIMIT);
        Optional<TopicStateResult> deterministic = classifyByRules(history);
        if (deterministic.isPresent()) return deterministic.get();

        try {
            String response = llmModelFactory.createUtilityChatModel().chat(buildClassifierPrompt(history));
            TopicState state = parseState(response);
            if (state != null) {
                return new TopicStateResult(state, "local_llm", true);
            }
            log.debug("话题状态分类返回格式无效，使用保守降级: {}", abbreviate(response));
        } catch (Exception e) {
            log.debug("话题状态分类调用失败，使用保守降级: {}", e.getMessage());
        }
        return new TopicStateResult(TopicState.OPEN, "classifier_fallback", false);
    }

    static Optional<TopicStateResult> classifyByRules(List<History> history) {
        if (history == null || history.isEmpty()) {
            return Optional.of(new TopicStateResult(TopicState.CLOSED, "empty_history", false));
        }

        History last = history.get(history.size() - 1);
        String content = normalize(last.getContent());
        if ("user".equals(last.getSender())) {
            if (CONTINUE_SIGNAL.matcher(content).find()) {
                return Optional.of(new TopicStateResult(TopicState.OPEN, "user_requested_continuation", false));
            }
            if (isClosedSignal(content)) {
                return Optional.of(new TopicStateResult(TopicState.CLOSED, "user_closed_topic", false));
            }
            // 用户消息还没有助手回答，视为尚未结束。
            return Optional.of(new TopicStateResult(TopicState.OPEN, "user_message_pending", false));
        }

        if ("assistant".equals(last.getSender()) && endsWithQuestion(content)) {
            return Optional.of(new TopicStateResult(TopicState.OPEN, "assistant_question_pending", false));
        }
        if ("assistant".equals(last.getSender())) {
            if (ASSISTANT_CLOSURE.matcher(content).find()) {
                return Optional.of(new TopicStateResult(TopicState.CLOSED, "assistant_closed_topic", false));
            }
            if (history.size() >= 2) {
                History previous = history.get(history.size() - 2);
                if ("user".equals(previous.getSender()) && isClosedSignal(normalize(previous.getContent()))) {
                    return Optional.of(new TopicStateResult(TopicState.CLOSED, "user_closed_before_reply", false));
                }
            }
        }
        return Optional.empty();
    }

    private String buildClassifierPrompt(List<History> history) {
        StringBuilder transcript = new StringBuilder();
        for (History message : history) {
            String role = "assistant".equals(message.getSender()) ? "助手" : "用户";
            String content = normalize(message.getContent());
            if (content.length() > MAX_MESSAGE_CHARS) content = content.substring(0, MAX_MESSAGE_CHARS);
            transcript.append(role).append("：").append(content).append('\n');
        }
        return """
                判断下面这段对话的“当前话题”是否已经自然结束。
                只输出严格 JSON：{"state":"open|closed"}。

                open：仍有未回答的问题、助手正在等待用户回答、用户要求继续/展开、或上一轮明显还有待办。
                closed：问题已经完整回答且没有待确认事项，用户已致谢/告别/表示结束，或没有可自然延续的具体话题。
                不要因为可以随便追问就判为 open。

                对话：
                """ + transcript;
    }

    private TopicState parseState(String response) {
        if (response == null || response.isBlank()) return null;
        try {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = objectMapper.readTree(response.substring(start, end + 1));
                String value = node.path("state").asText("").toLowerCase(Locale.ROOT);
                if ("open".equals(value)) return TopicState.OPEN;
                if ("closed".equals(value)) return TopicState.CLOSED;
            }
        } catch (Exception ignored) {
        }
        String normalized = response.strip().toLowerCase(Locale.ROOT);
        if ("open".equals(normalized)) return TopicState.OPEN;
        if ("closed".equals(normalized)) return TopicState.CLOSED;
        return null;
    }

    private static boolean endsWithQuestion(String value) {
        String normalized = value.replaceAll("[\\s\"'”’）)】》>]+$", "");
        return normalized.endsWith("?") || normalized.endsWith("？");
    }

    private static boolean isClosedSignal(String value) {
        if (CLOSED_SIGNAL.matcher(value).find()) return true;
        if (value.length() > 24) return false;
        return value.matches(".*(谢谢|感谢|明白|懂了|知道了|收到).*")
                && !CONTINUE_SIGNAL.matcher(value).find();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        return value.length() <= 120 ? value : value.substring(0, 120) + "...";
    }

    public enum TopicState {
        OPEN, CLOSED
    }

    public record TopicStateResult(TopicState state, String reason, boolean llmUsed) {
        public boolean isOpen() {
            return state == TopicState.OPEN;
        }
    }
}
