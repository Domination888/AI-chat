package org.example.aichat.skill;

import dev.langchain4j.data.message.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.mcp.McpClientManager;
import org.example.aichat.util.WebSearchHelper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 在对话请求入口，按已启用技能执行预取并生成可注入的上下文块。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillRuntimeService {

    private final SkillService skillService;
    private final AiDailySkillService aiDailySkillService;

    public record PreInjection(String blockTitle, String body, String tailHint, String logTag) {
    }

    public Optional<PreInjection> tryPreInject(String message, List<ChatMessage> history,
                                               boolean supplementWebSearch,
                                               McpClientManager mcp) {
        List<SkillManifest> enabled = skillService.enabledSkills();
        if (enabled.isEmpty() || message == null) {
            return Optional.empty();
        }

        if (aiDailySkillService.isEnabled() && aiDailySkillService.matchesDailyQuestionOrFollowUp(message, history)) {
            Optional<String> body = aiDailySkillService.buildNewsInjection(message, history, mcp);
            if (body.isPresent() && !body.get().isBlank()) {
                log.info("技能 ai-daily-juya 预取成功，长度={}", body.get().length());
                return Optional.of(new PreInjection(
                        "【AI 日报 · 技能 ai-daily-juya】",
                        body.get(),
                        "请根据以上 AI 日报回答用户的今日新闻问题；只概括关键看点，可附来源链接。若内容与问题不相关，请说明日报未覆盖，不要编造。",
                        "ai-daily-juya"));
            }
            log.debug("技能 ai-daily-juya 匹配但未读取到日报");
        }

        if (WeatherSkillHandler.isEnabled(enabled) && WeatherSkillHandler.matches(message, history)) {
            String body = WeatherSkillHandler.buildContext(message, history, supplementWebSearch, mcp);
            if (body != null && !body.isBlank()) {
                log.info("技能 weather-lookup 预取成功，长度={}", body.length());
                return Optional.of(new PreInjection(
                        "【天气查询结果 · 技能 weather-lookup】",
                        body,
                        WeatherSkillHandler.injectionHint(),
                        "weather-lookup"));
            }
            log.debug("技能 weather-lookup 匹配但未拉到数据，城市={}", WeatherSkillHandler.resolveCity(message, history));
        }

        return Optional.empty();
    }

    /** 是否应由 weather-lookup 处理，从而跳过通用联网预搜索。 */
    public boolean shouldSkipGenericPreSearch(String message, List<ChatMessage> history) {
        List<SkillManifest> enabled = skillService.enabledSkills();
        if (!WeatherSkillHandler.isEnabled(enabled)) {
            return aiDailySkillService.isEnabled() && aiDailySkillService.matchesDailyQuestionOrFollowUp(message, history);
        }
        return WeatherSkillHandler.matches(message, history)
                || (aiDailySkillService.isEnabled() && aiDailySkillService.matchesDailyQuestionOrFollowUp(message, history));
    }

    public Optional<PreInjection> tryGenericWebPreSearch(String message, boolean supplementWebSearch,
                                                         McpClientManager mcp) {
        if (!supplementWebSearch || !WebSearchHelper.shouldPreSearch(message)) {
            return Optional.empty();
        }
        if (WebSearchHelper.isWeatherIntent(message)) {
            return Optional.empty();
        }
        String searchQuery = WebSearchHelper.optimizeQuery(message);
        String searchResult = mcp.webSearch(searchQuery);
        String body = WebSearchHelper.buildSearchContext(searchResult);
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        log.info("通用联网预搜索 query={}, 长度={}", searchQuery, body.length());
        return Optional.of(new PreInjection(
                "【联网搜索结果】",
                body,
                "请根据以上搜索结果直接回答用户问题，并引用相关来源；若与问题无关或为空，说明未能查到并勿编造来源。不要只回复「好的，我已了解」。",
                "web-search"));
    }
}
