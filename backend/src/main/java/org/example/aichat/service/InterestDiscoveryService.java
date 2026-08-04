package org.example.aichat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.config.LlmModelFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterestDiscoveryService {
    private static final Pattern PRIVATE_VALUE = Pattern.compile(
            "([\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|1[3-9]\\d{9}|\\d{6,}|详细地址|身份证|银行卡)", Pattern.CASE_INSENSITIVE);

    private final LlmModelFactory llmModelFactory;
    private final ObjectMapper objectMapper;

    public List<Suggestion> discover(List<String> userMessages) {
        if (userMessages == null || userMessages.isEmpty()) return List.of();
        try {
            String prompt = "从以下用户消息中提取适合长期主动搜索的兴趣主题。输出严格 JSON：" +
                    "{\"interests\":[{\"topic\":\"简短主题\",\"weight\":0.0,\"evidence\":\"不含隐私的概括\"}]}。" +
                    "最多12项；忽略姓名、地址、联系方式、账号、健康和财务隐私；不要把一次性办事请求当兴趣。\n\n" +
                    String.join("\n", userMessages);
            String content = llmModelFactory.createUtilityChatModel(8000)
                    .chat(SystemMessage.from("你只输出 JSON，不泄露用户隐私。"), UserMessage.from(prompt))
                    .aiMessage().text();
            int start = content.indexOf('{'), end = content.lastIndexOf('}');
            if (start < 0 || end <= start) return fallback(userMessages);
            JsonNode interests = objectMapper.readTree(content.substring(start, end + 1)).path("interests");
            List<Suggestion> out = new ArrayList<>();
            for (JsonNode item : interests) {
                String topic = sanitize(item.path("topic").asText(""));
                String evidence = sanitize(item.path("evidence").asText(""));
                if (topic.length() < 2 || topic.length() > 64 || PRIVATE_VALUE.matcher(topic).find()) continue;
                double weight = Math.max(0.2, Math.min(1.0, item.path("weight").asDouble(0.55)));
                out.add(new Suggestion(topic, weight, evidence));
                if (out.size() >= 12) break;
            }
            return out.isEmpty() ? fallback(userMessages) : out;
        } catch (Exception e) {
            log.debug("兴趣提取降级为本地规则: {}", e.getMessage());
            return fallback(userMessages);
        }
    }

    private List<Suggestion> fallback(List<String> messages) {
        List<Suggestion> out = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && out.size() < 6; i--) {
            String message = sanitize(messages.get(i));
            for (String part : message.split("[，。！？；,.!?;：:]")) {
                String topic = part.replaceAll("^(我想|我喜欢|我关注|帮我|请问|能不能|有没有|关于)", "").strip();
                if (topic.length() >= 2 && topic.length() <= 24 && !PRIVATE_VALUE.matcher(topic).find()
                        && out.stream().noneMatch(s -> s.topic().equals(topic))) {
                    out.add(new Suggestion(topic, 0.45, "来自近期对话"));
                }
            }
        }
        return out;
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        return PRIVATE_VALUE.matcher(value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").strip())
                .replaceAll("[已隐藏]");
    }

    public record Suggestion(String topic, double weight, String evidence) { }
}
