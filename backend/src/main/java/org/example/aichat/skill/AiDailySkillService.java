package org.example.aichat.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.mcp.AppPaths;
import org.example.aichat.search.SearchRequest;
import org.example.aichat.search.SearchResponse;
import org.example.aichat.search.WebSearchGateway;
import org.example.aichat.service.ProactiveChatService;
import org.example.aichat.service.ProactiveResearchService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import dev.langchain4j.data.message.ChatMessage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ai-daily-juya skill 的运行时：读取 RSS，并把日报作为主动话题或新闻问答上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDailySkillService {

    public static final String SKILL_NAME = "ai-daily-juya";

    private static final String DEFAULT_RSS_URL = "https://daily.juya.uk/rss.xml";
    private static final Pattern MARKDOWN_ITEM = Pattern.compile(
            "(?ms)^### \\[(.+?)]\\((.+?)\\).*?\\n>\\s*(.*?)\\n\\n(.*?)(?=\\n---\\n|\\n### |\\n## |\\z)");
    private static final Pattern NEWS_INTENT = Pattern.compile(
            "(今天|今日|当天|最新).*(新闻|资讯|日报|快讯|动态)|" +
                    "(有什么|有啥|哪些).*(新闻|资讯|日报|快讯|动态)|" +
                    "(AI|ai|人工智能|科技).*(新闻|资讯|日报|快讯|动态)|" +
                    "(新闻|资讯|日报|快讯|动态).*(AI|ai|人工智能|科技)");
    private static final Pattern DETAIL_INTENT = Pattern.compile(
            "(具体|详细|展开|细节|内容|原文|链接|怎么回事|说说|讲讲|介绍|第[一二三四五六七八九十0-9]+|#\\d+|\\b\\d+\\b)");

    private final SkillService skillService;
    private final AppPaths appPaths;
    private final ObjectProvider<ProactiveChatService> proactiveChatServiceProvider;
    private final ObjectProvider<ProactiveResearchService> proactiveResearchServiceProvider;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-daily-skill-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @jakarta.annotation.PostConstruct
    public void start() {
        scheduleNextRun();
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    public boolean isEnabled() {
        return enabledManifest().isPresent();
    }

    public boolean matchesNewsQuestion(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return NEWS_INTENT.matcher(message).find();
    }

    public boolean matchesDailyQuestionOrFollowUp(String message, List<ChatMessage> history) {
        if (matchesNewsQuestion(message)) {
            return true;
        }
        return isDetailFollowUp(message) && recentHistoryMentionsDaily(history);
    }

    public Optional<String> buildNewsInjection(String message, List<ChatMessage> history, WebSearchGateway searchGateway) {
        Optional<DailyDigest> digest = fetchLatestDigest();
        if (digest.isEmpty()) {
            return Optional.empty();
        }
        String block = formatDigestBlock(digest.get());
        Optional<DailyItem> detailItem = isDetailFollowUp(message)
                ? resolveReferencedItem(message, history, digest.get())
                : Optional.empty();
        if (detailItem.isPresent()) {
            block += "\n\n" + buildSourceSearchBlock(detailItem.get(), searchGateway);
        } else if (isDetailFollowUp(message)) {
            block += "\n\n【追问处理提示】\n用户在追问具体新闻，但未能可靠定位到日报中的某一条。请先请用户说明想看第几条或哪个标题，不要猜。";
        }
        return Optional.of(block);
    }

    public Optional<DailyDigest> fetchLatestDigest() {
        Optional<SkillManifest> manifest = enabledManifest();
        if (manifest.isEmpty()) {
            return Optional.empty();
        }
        String rssUrl = sourceUrl(manifest.get());
        int maxItems = maxItems(manifest.get());
        ZoneId zone = resolveZone(manifest.get());
        LocalDate today = LocalDate.now(zone);
        try {
            Optional<IssueRef> issue = findTodayIssue(rssUrl, today);
            if (issue.isEmpty()) {
                log.warn("AI 日报 RSS 中未找到今天的日报: date={}, rss={}", today, rssUrl);
                return Optional.empty();
            }
            List<DailyItem> items = readIssueMarkdown(issue.get(), today, maxItems);
            if (items.isEmpty()) {
                log.warn("AI 日报正文未解析到条目: {}", issue.get().markdownUrl());
                return Optional.empty();
            }
            return Optional.of(new DailyDigest(today.toString(), issue.get().title(), issue.get().link(), items));
        } catch (Exception e) {
            log.warn("读取 AI 日报失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean triggerNow() {
        Optional<DailyDigest> digest = fetchLatestDigest();
        if (digest.isEmpty()) {
            return false;
        }
        return triggerDigest(digest.get(), false);
    }

    private void scheduleNextRun() {
        long delayMs = millisUntilNextRun();
        scheduler.schedule(() -> {
            try {
                runScheduledRead();
            } catch (Exception e) {
                log.warn("AI 日报定时任务失败: {}", e.getMessage());
            } finally {
                scheduleNextRun();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        log.info("AI 日报 skill 下一次定时检查将在 {} 秒后执行", Math.max(1, delayMs / 1000));
    }

    private void runScheduledRead() {
        Optional<SkillManifest> manifest = enabledManifest();
        if (manifest.isEmpty() || manifest.get().getSchedule() == null || !manifest.get().getSchedule().isEnabled()) {
            return;
        }
        Optional<DailyDigest> digest = fetchLatestDigest();
        if (digest.isEmpty()) {
            return;
        }
        triggerDigest(digest.get(), true);
    }

    private boolean triggerDigest(DailyDigest digest, boolean dedupe) {
        if (dedupe && alreadyTriggered(digest.key())) {
            log.info("AI 日报已触发过，跳过: {}", digest.key());
            return false;
        }
        Optional<SkillManifest> manifest = enabledManifest();
        if (manifest.isEmpty() || manifest.get().getProactive() == null || !manifest.get().getProactive().isEnabled()) {
            return false;
        }
        List<org.example.aichat.search.SearchSource> sources = digest.items().stream()
                .filter(item -> item.link() != null && !item.link().isBlank())
                .limit(3)
                .map(item -> org.example.aichat.search.SearchSource.builder()
                        .title(item.title()).url(item.link()).snippet(item.summary())
                        .excerpts(item.detail().isBlank() ? List.of(item.summary()) : List.of(item.detail()))
                        .score(0.85).build())
                .toList();
        ProactiveChatService proactiveChatService = proactiveChatServiceProvider.getIfAvailable();
        ProactiveResearchService proactiveResearchService = proactiveResearchServiceProvider.getIfAvailable();
        if (proactiveChatService == null || proactiveResearchService == null || sources.isEmpty()) {
            return false;
        }
        boolean queued = proactiveChatService.activeTargets().stream()
                .map(ProactiveChatService.ActiveTarget::userId).distinct()
                .map(userId -> proactiveResearchService.enqueueExternalCandidate(
                        userId, "AI 日报", digest.title(), formatDigestBlock(digest),
                        "来自今天的 AI 日报", sources, 85.0))
                .reduce(false, Boolean::logicalOr);
        if (queued && dedupe) {
            saveLastTriggeredKey(digest.key());
        }
        return queued;
    }

    private String buildProactivePrompt(SkillManifest manifest, DailyDigest digest) {
        String template = manifest.getProactive() == null ? "" : manifest.getProactive().getPromptTemplate();
        if (template == null || template.isBlank()) {
            template = "[System: 你刚读完今天的 AI 日报。请挑 1-2 个和用户可能相关的看点，自然主动开个话题。不要逐条播报全文。]";
        }
        return template.strip() + "\n\n" + formatDigestBlock(digest) + "\n\n请基于以上日报内容主动开场，不要编造未列出的新闻。";
    }

    private String formatDigestBlock(DailyDigest digest) {
        StringBuilder sb = new StringBuilder();
        sb.append("日报标题：").append(digest.title()).append("\n");
        sb.append("来源：").append(digest.sourceUrl()).append("\n");
        sb.append("条目：\n");
        int idx = 1;
        for (DailyItem item : digest.items()) {
            sb.append(idx++).append(". ").append(item.title()).append("\n");
            if (!item.summary().isBlank()) {
                sb.append("   摘要：").append(item.summary()).append("\n");
            }
            if (!item.detail().isBlank()) {
                sb.append("   正文：").append(item.detail()).append("\n");
            }
            if (!item.link().isBlank()) {
                sb.append("   链接：").append(item.link()).append("\n");
            }
        }
        return sb.toString().strip();
    }

    private String buildSourceSearchBlock(DailyItem item, WebSearchGateway searchGateway) {
        StringBuilder sb = new StringBuilder();
        sb.append("【日报原始链接检索 · ").append(item.title()).append("】\n");
        sb.append("日报条目链接：").append(item.link()).append("\n");
        if (searchGateway == null || item.link().isBlank()) {
            sb.append("未执行联网检索：缺少搜索工具或原始链接。");
            return sb.toString();
        }
        String query = item.title() + " " + item.link();
        SearchResponse response = searchGateway.search(SearchRequest.builder()
                .query(query).maxSources(3).build());
        String context = response.getContextText();
        if (!response.hasSources() || context == null || context.isBlank()) {
            sb.append("联网检索未返回可用结果。回答时只能基于日报正文，并说明未能打开/查到原始链接补充内容。");
        } else {
            sb.append("搜索 query：").append(query).append("\n");
            sb.append(context);
            sb.append("\n\n回答要求：优先结合以上原始链接检索结果和日报正文讲具体内容；若两者冲突，以原始链接检索结果为准，并说明不确定处。");
        }
        return sb.toString().strip();
    }

    private boolean isDetailFollowUp(String message) {
        return message != null && DETAIL_INTENT.matcher(message).find();
    }

    private boolean recentHistoryMentionsDaily(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            String text = String.valueOf(history.get(i));
            if (text.contains("AI 日报") || text.contains("AI早报") || text.contains("AI 早报")
                    || text.contains("今日新闻") || text.contains("今天的新闻")
                    || text.contains("Anthropic") || text.contains("OpenAI") || text.contains("微软")) {
                return true;
            }
        }
        return false;
    }

    private Optional<DailyItem> resolveReferencedItem(String message, List<ChatMessage> history, DailyDigest digest) {
        if (message == null || digest.items().isEmpty()) {
            return Optional.empty();
        }
        Optional<Integer> explicitIndex = referencedIndex(message);
        if (explicitIndex.isPresent()) {
            int idx = explicitIndex.get();
            if (idx >= 1 && idx <= digest.items().size()) {
                return Optional.of(digest.items().get(idx - 1));
            }
        }
        Optional<DailyItem> byMessage = bestTitleMatch(message, digest.items());
        if (byMessage.isPresent()) {
            return byMessage;
        }
        String recent = recentHistoryText(history);
        return bestTitleMatch(recent + " " + message, digest.items());
    }

    private Optional<Integer> referencedIndex(String message) {
        Matcher hash = Pattern.compile("#\\s*(\\d+)").matcher(message);
        if (hash.find()) {
            return Optional.of(Integer.parseInt(hash.group(1)));
        }
        Matcher digit = Pattern.compile("第\\s*(\\d+)\\s*(条|个|则)?|\\b(\\d{1,2})\\b").matcher(message);
        if (digit.find()) {
            String raw = digit.group(1) != null ? digit.group(1) : digit.group(3);
            return Optional.of(Integer.parseInt(raw));
        }
        String[] cn = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        for (int i = 0; i < cn.length; i++) {
            if (message.contains("第" + cn[i])) {
                return Optional.of(i + 1);
            }
        }
        return Optional.empty();
    }

    private Optional<DailyItem> bestTitleMatch(String text, List<DailyItem> items) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        DailyItem best = null;
        int bestScore = 0;
        for (DailyItem item : items) {
            int score = keywordScore(text, item.title());
            if (score > bestScore) {
                best = item;
                bestScore = score;
            }
        }
        return bestScore >= 2 ? Optional.of(best) : Optional.empty();
    }

    private int keywordScore(String text, String title) {
        int score = 0;
        for (String token : title.split("[\\s，。、“”《》（）()：:;；/|｜\\-]+")) {
            String compact = token.trim();
            if (compact.length() >= 2 && text.contains(compact)) {
                score++;
            }
        }
        return score;
    }

    private String recentHistoryText(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, history.size() - 4);
        for (int i = start; i < history.size(); i++) {
            sb.append(history.get(i)).append("\n");
        }
        return sb.toString();
    }

    private Optional<IssueRef> findTodayIssue(String rssUrl, LocalDate date) throws Exception {
        Document doc = readXml(rssUrl);
        NodeList nodes = doc.getElementsByTagName("item");
        String dateText = date.toString();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            String title = clean(text(item, "title"));
            String link = clean(text(item, "link"));
            if (dateText.equals(title) || link.contains("/" + dateText + "/")) {
                return Optional.of(new IssueRef(
                        title.isBlank() ? "AI 早报 " + dateText : title,
                        link,
                        deriveMarkdownUrl(link, dateText),
                        trimTo(clean(text(item, "description")), 1200)));
            }
        }
        return Optional.empty();
    }

    private Document readXml(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "AI-Chat/ai-daily-skill")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();
        return doc;
    }

    private List<DailyItem> readIssueMarkdown(IssueRef issue, LocalDate date, int maxItems) throws Exception {
        String markdown = readText(issue.markdownUrl());
        String title = extractMarkdownTitle(markdown).orElse("AI 早报 " + date);
        List<DailyItem> items = parseMarkdownItems(markdown, maxItems);
        if (!items.isEmpty()) {
            return items;
        }
        return List.of(new DailyItem(title, issue.link(), issue.rssSummary(), "", ""));
    }

    private String readText(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "AI-Chat/ai-daily-skill")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + url);
        }
        return response.body();
    }

    private List<DailyItem> parseMarkdownItems(String markdown, int maxItems) {
        List<DailyItem> items = new ArrayList<>();
        Matcher matcher = MARKDOWN_ITEM.matcher(markdown);
        while (matcher.find() && items.size() < Math.max(1, maxItems)) {
            String title = cleanMarkdown(matcher.group(1));
            String link = matcher.group(2).trim();
            String summary = cleanMarkdown(matcher.group(3));
            String detail = cleanMarkdown(stripRelatedLinks(matcher.group(4)));
            items.add(new DailyItem(title, link, trimTo(summary, 520), trimTo(detail, 1200), ""));
        }
        return items;
    }

    private Optional<String> extractMarkdownTitle(String markdown) {
        Matcher matcher = Pattern.compile("(?m)^#\\s+(.+)$").matcher(markdown);
        return matcher.find() ? Optional.of(cleanMarkdown(matcher.group(1))) : Optional.empty();
    }

    private String deriveMarkdownUrl(String issueUrl, String dateText) {
        if (issueUrl == null || issueUrl.isBlank()) {
            return "https://daily.juya.uk/markdown/" + dateText + ".md";
        }
        try {
            URI uri = URI.create(issueUrl);
            return uri.getScheme() + "://" + uri.getHost() + "/markdown/" + dateText + ".md";
        } catch (Exception e) {
            return "https://daily.juya.uk/markdown/" + dateText + ".md";
        }
    }

    private Optional<SkillManifest> enabledManifest() {
        return skillService.get(SKILL_NAME).filter(SkillManifest::isEnabled);
    }

    private String sourceUrl(SkillManifest manifest) {
        if (manifest.getSource() != null && manifest.getSource().getUrl() != null
                && !manifest.getSource().getUrl().isBlank()) {
            return manifest.getSource().getUrl();
        }
        return DEFAULT_RSS_URL;
    }

    private int maxItems(SkillManifest manifest) {
        if (manifest.getProactive() == null || manifest.getProactive().getMaxItems() <= 0) {
            return 20;
        }
        return Math.max(20, manifest.getProactive().getMaxItems());
    }

    private long millisUntilNextRun() {
        SkillManifest manifest = skillService.get(SKILL_NAME).orElse(null);
        ZoneId zone = resolveZone(manifest);
        SkillManifest.ScheduleConfig schedule = manifest == null ? null : manifest.getSchedule();
        int hour = schedule == null || schedule.getHour() == null ? 10 : clamp(schedule.getHour(), 0, 23);
        int minute = schedule == null || schedule.getMinute() == null ? 0 : clamp(schedule.getMinute(), 0, 59);
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute)).atZone(zone);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }

    private ZoneId resolveZone(SkillManifest manifest) {
        String zone = manifest != null && manifest.getSchedule() != null ? manifest.getSchedule().getZone() : null;
        if (zone == null || zone.isBlank()) {
            zone = "Asia/Shanghai";
        }
        try {
            return ZoneId.of(zone);
        } catch (Exception e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean alreadyTriggered(String key) {
        try {
            Path state = stateFile();
            return Files.isRegularFile(state) && Files.readString(state, StandardCharsets.UTF_8).trim().equals(key);
        } catch (Exception e) {
            return false;
        }
    }

    private void saveLastTriggeredKey(String key) {
        try {
            Path state = stateFile();
            Files.createDirectories(state.getParent());
            Files.writeString(state, key == null ? "" : key, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("保存 AI 日报触发状态失败: {}", e.getMessage());
        }
    }

    private Path stateFile() {
        return appPaths.skillsDir().resolve(SKILL_NAME).resolve("state.json");
    }

    private String text(Element item, String tagName) {
        NodeList nodes = item.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || nodes.item(0) == null) {
            return "";
        }
        return nodes.item(0).getTextContent();
    }

    private Optional<LocalDate> parsePubDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate());
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private String cleanMarkdown(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("!\\[[^]]*]\\([^)]+\\)", " ");
        s = s.replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "$1");
        s = s.replace("`", "");
        s = s.replaceAll("(?m)^#+\\s*", "");
        s = s.replaceAll("(?m)^>\\s*", "");
        s = s.replaceAll("(?m)^-\\s*", "");
        s = s.replaceAll("\\{([^{}|]+)\\|\"[^\"]+\"}", "$1");
        return s.replaceAll("\\s+", " ").trim();
    }

    private String stripRelatedLinks(String raw) {
        if (raw == null) {
            return "";
        }
        int idx = raw.indexOf("相关链接：");
        return idx >= 0 ? raw.substring(0, idx) : raw;
    }

    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("(?is)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s.replaceAll("\\s+", " ").trim();
    }

    private String trimTo(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String compact = text.strip();
        if (compact.length() <= maxChars) {
            return compact;
        }
        return compact.substring(0, maxChars) + "...";
    }

    public record DailyDigest(String key, String title, String sourceUrl, List<DailyItem> items) {
    }

    private record IssueRef(String title, String link, String markdownUrl, String rssSummary) {
    }

    public record DailyItem(String title, String link, String summary, String detail, String pubDateRaw) {
    }
}
