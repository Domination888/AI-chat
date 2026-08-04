package org.example.aichat.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.config.DynamicEmbeddingModel;
import org.example.aichat.config.LlmModelFactory;
import org.example.aichat.util.WebSearchHelper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebResearchService implements WebSearchGateway {

    private static final Pattern FRESHNESS = Pattern.compile(
            "(最新|最近|今天|今日|昨日|昨天|本周|本月|当前|现在|目前|新闻|公告|发布|更新|版本|价格|股价|汇率|赛程|比分|202[0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "^(它|这个|这件事|这条|上面|刚才|其中|具体|详细|展开|然后|后来|怎么样|为什么|真的吗|来源).{0,18}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2}[-/.年]\\d{1,2}[-/.月]\\d{1,2})");
    private static final Pattern OFFICIAL_REQUEST = Pattern.compile(
            "(官方|官网|文档|official|documentation|docs|reference|manual|guide)", Pattern.CASE_INSENSITIVE);
    private static final int RRF_K = 60;
    private static final double MIN_SOURCE_SCORE = 0.38;

    private final SearchProperties properties;
    private final DynamicEmbeddingModel embeddingModel;
    private final LlmModelFactory llmModelFactory;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ExecutorService pageExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "web-research-page-fetch");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService rerankExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "web-research-rerank");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, CacheEntry<SearchResponse>> resultCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<PageContent>> pageCache = new ConcurrentHashMap<>();
    private final Map<String, Long> engineSuccesses = new ConcurrentHashMap<>();
    private final Map<String, Long> engineFailures = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    @PreDestroy
    public void shutdown() {
        pageExecutor.shutdownNow();
        rerankExecutor.shutdownNow();
    }

    @Override
    public SearchResponse search(SearchRequest request) {
        long startedAt = System.currentTimeMillis();
        String query = request == null ? null : request.getQuery();
        if (query == null || query.isBlank()) {
            return empty("invalid_request", List.of(), "搜索问题不能为空", startedAt);
        }
        query = query.strip();
        String cacheKey = cacheKey(request, query);
        SearchResponse cached = getCached(resultCache, cacheKey);
        if (cached != null) {
            notify(request, new SearchProgress("complete", "使用缓存的联网资料", cached.getSources()));
            return cached;
        }

        long deadline = startedAt + Math.max(1000, properties.getTotalTimeoutMs() - 300L);
        try {
            notify(request, SearchProgress.of("planning", "正在规划搜索问题"));
            QueryPlan plan = planQueries(query, request.getConversationContext(), request.getLanguage(), request.getTimeRange());
            notify(request, SearchProgress.of("searching", "正在通过本地 SearXNG 搜索"));
            List<RankedResult> recalled = recall(plan, deadline);
            if (recalled.isEmpty()) {
                SearchResponse response = empty("no_results", plan.queries(), "未找到可靠的搜索结果", startedAt);
                putCached(resultCache, cacheKey, response, Duration.ofMinutes(properties.getResultCacheMinutes()));
                notify(request, SearchProgress.of("error", "未找到可靠的联网来源"));
                return response;
            }

            notify(request, SearchProgress.of("reading_pages", "正在读取公开网页原文"));
            enrichPages(recalled, startedAt);
            notify(request, SearchProgress.of("reranking", "正在进行本地语义重排"));
            int maxSources = request.getMaxSources() == null
                    ? properties.getMaxSources()
                    : Math.max(1, Math.min(request.getMaxSources(), properties.getMaxSources()));
            String rankingQuery = contextualizeFollowUp(query, request.getConversationContext());
            List<SearchSource> sources = rerank(rankingQuery, recalled, maxSources, deadline);
            if (sources.isEmpty()) {
                SearchResponse response = empty("no_reliable_sources", plan.queries(), "搜索结果缺少可验证内容", startedAt);
                notify(request, SearchProgress.of("error", "搜索结果缺少可验证内容"));
                return response;
            }

            SearchResponse response = SearchResponse.builder()
                    .status("ok")
                    .plannedQueries(plan.queries())
                    .sources(sources)
                    .contextText(formatContext(query, sources))
                    .diagnostics(Map.of(
                            "recalled", recalled.size(),
                            "pageRead", sources.stream().filter(SearchSource::isPageRead).count(),
                            "costMs", System.currentTimeMillis() - startedAt))
                    .build();
            putCached(resultCache, cacheKey, response, Duration.ofMinutes(properties.getResultCacheMinutes()));
            notify(request, new SearchProgress("complete", "联网资料准备完成", sources));
            return response;
        } catch (Exception e) {
            lastError.set(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("Search-RAG failed, query={}: {}", abbreviate(query), lastError.get());
            notify(request, SearchProgress.of("error", "联网检索失败：" + lastError.get()));
            return empty("error", List.of(query), "联网检索不可用，请稍后重试", startedAt);
        }
    }

    @Override
    public SearchHealth health() {
        boolean available = false;
        try {
            URI uri = URI.create(trimSlash(properties.getSearxngUrl()) + "/healthz");
            HttpResponse<Void> response = httpClient.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
            available = response.statusCode() >= 200 && response.statusCode() < 500;
        } catch (Exception ignored) {
            // Some SearXNG builds do not expose /healthz; recent successful engines still mean usable.
            available = !engineSuccesses.isEmpty();
        }
        return new SearchHealth(available, properties.getSearxngUrl(), Map.copyOf(engineSuccesses),
                Map.copyOf(engineFailures), lastError.get());
    }

    QueryPlan planQueries(String query, List<String> history, String language, String requestedTimeRange) {
        String fallback = contextualizeFollowUp(query, history);
        String effectiveLanguage = language == null || language.isBlank()
                ? (containsChinese(fallback) ? "zh-CN" : "en-US") : language;
        String timeRange = requestedTimeRange == null || requestedTimeRange.isBlank()
                ? (FRESHNESS.matcher(fallback).find() ? "month" : "") : requestedTimeRange;

        if (properties.isQueryPlannerEnabled()) {
            try {
                QueryPlan planned = callLocalPlanner(query, history, effectiveLanguage, timeRange);
                if (planned != null && !planned.queries().isEmpty()) {
                    return withOfficialQuery(planned, fallback);
                }
            } catch (Exception e) {
                log.debug("Local query planner unavailable, using deterministic fallback: {}", e.getMessage());
            }
        }

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(WebSearchHelper.optimizeQuery(fallback));
        if (!timeRange.isBlank() && queries.size() < properties.getMaxQueries()) {
            queries.add(WebSearchHelper.optimizeQuery(fallback) + " " + LocalDate.now().getYear());
        }
        return withOfficialQuery(new QueryPlan(limitQueries(queries), effectiveLanguage, timeRange), fallback);
    }

    private QueryPlan withOfficialQuery(QueryPlan plan, String originalQuery) {
        String domain = officialDomainFor(originalQuery);
        if (domain.isBlank() || !OFFICIAL_REQUEST.matcher(originalQuery).find()) return plan;
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(sanitizeQuery(originalQuery) + " site:" + domain);
        queries.addAll(plan.queries());
        return new QueryPlan(limitQueries(queries), plan.language(), plan.timeRange());
    }

    private QueryPlan callLocalPlanner(String query, List<String> history, String language, String timeRange) throws Exception {
        String context = history == null ? "" : String.join("\n", history.stream()
                .filter(s -> s != null && !s.isBlank()).skip(Math.max(0, history.size() - 8L)).toList());
        String instruction = "你是搜索查询规划器。根据当前问题和最近对话，输出严格 JSON：" +
                "{\"queries\":[\"...\"],\"language\":\"zh-CN或en-US\",\"timeRange\":\"day/week/month/year或空\"}。" +
                "最多3个简短查询；不要无条件添加‘最新’，只有问题明确要求当前或新闻时才使用时间范围；不要回答问题。";
        String content = llmModelFactory.createUtilityChatModel(properties.getPlannerTimeoutMs())
                .chat(SystemMessage.from(instruction),
                        UserMessage.from("最近对话：\n" + context + "\n\n当前问题：" + query))
                .aiMessage().text();
        if (content == null) return null;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        JsonNode json = objectMapper.readTree(content.substring(start, end + 1));
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (JsonNode node : json.path("queries")) {
            String value = sanitizeQuery(node.asText(""));
            if (!value.isBlank()) queries.add(value);
        }
        if (queries.isEmpty()) return null;
        String plannedLanguage = json.path("language").asText(language);
        String plannedRange = normalizeTimeRange(json.path("timeRange").asText(timeRange));
        if (!FRESHNESS.matcher(query).find() && (timeRange == null || timeRange.isBlank())) {
            plannedRange = "";
        }
        return new QueryPlan(limitQueries(queries), plannedLanguage, plannedRange);
    }

    private List<RankedResult> recall(QueryPlan plan, long deadline) {
        Map<String, RankedResult> merged = new LinkedHashMap<>();
        Map<String, RankedResult> byTitle = new HashMap<>();
        for (String query : plan.queries()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            try {
                URI uri = buildSearxUri(query, plan.language(), plan.timeRange());
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMillis(Math.max(1, Math.min(remaining, 10000))))
                        .header("Accept", "application/json")
                        .header("User-Agent", "AI-Chat-Search-RAG/2.0")
                        .GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) throw new IllegalStateException("SearXNG HTTP " + response.statusCode());
                JsonNode root = objectMapper.readTree(response.body());
                Set<String> responsiveEngines = new HashSet<>();
                int rank = 0;
                for (JsonNode item : root.path("results")) {
                    if (++rank > properties.getResultsPerQuery()) break;
                    String url = item.path("url").asText("").strip();
                    String title = item.path("title").asText("").strip();
                    String snippet = item.path("content").asText("").replaceAll("\\s+", " ").strip();
                    if (url.isBlank() || (title.isBlank() && snippet.isBlank()) || !isPublicHttpUrl(url)) continue;
                    String key = normalizeUrl(url);
                    String titleKey = domainOf(url) + "|" + normalizeTitle(title);
                    RankedResult result = merged.get(key);
                    if (result == null && !titleKey.isBlank()) result = byTitle.get(titleKey);
                    if (result == null) {
                        result = new RankedResult(title, url, snippet);
                        merged.put(key, result);
                        if (!titleKey.isBlank()) byTitle.put(titleKey, result);
                    }
                    result.rrf += rrfContribution(item, rank);
                    if (result.title.isBlank() && !title.isBlank()) result.title = title;
                    if (snippet.length() > result.snippet.length()) result.snippet = snippet;
                    for (JsonNode engine : item.path("engines")) {
                        String name = engine.asText("");
                        if (!name.isBlank()) { result.engines.add(name); responsiveEngines.add(name); }
                    }
                    String engine = item.path("engine").asText("");
                    if (!engine.isBlank()) { result.engines.add(engine); responsiveEngines.add(engine); }
                    result.publishedAt = firstNonBlank(result.publishedAt,
                            item.path("publishedDate").asText(""), item.path("published_date").asText(""));
                }
                responsiveEngines.forEach(engine -> engineSuccesses.merge(engine, 1L, Long::sum));
                for (JsonNode unavailable : root.path("unresponsive_engines")) {
                    String engine = unavailable.isArray() ? unavailable.path(0).asText("") : unavailable.asText("");
                    if (!engine.isBlank()) engineFailures.merge(engine, 1L, Long::sum);
                }
            } catch (Exception e) {
                configuredEngines().forEach(engine -> engineFailures.merge(engine, 1L, Long::sum));
                lastError.set(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                log.debug("Search query failed and was skipped, query={}: {}", abbreviate(query), lastError.get());
            }
        }
        List<RankedResult> results = new ArrayList<>(merged.values());
        addOfficialSeed(plan, results);
        results.sort(Comparator.comparingDouble((RankedResult r) -> r.rrf).reversed());
        return results;
    }

    private void addOfficialSeed(QueryPlan plan, List<RankedResult> results) {
        String plannedText = String.join(" ", plan.queries());
        String domain = officialDomainFor(plannedText);
        if (domain.isBlank() || !OFFICIAL_REQUEST.matcher(plannedText).find()) return;
        boolean alreadyPresent = results.stream().anyMatch(result -> hostMatches(result.url, domain));
        if (alreadyPresent) return;
        String url = "https://" + domain + "/";
        RankedResult seed = new RankedResult("Official documentation: " + domain, url,
                "Official first-party source for " + plannedText);
        seed.engines.add("official-seed");
        seed.rrf = 3.0 / (RRF_K + 1);
        results.add(seed);
    }

    private void enrichPages(List<RankedResult> results, long startedAt) {
        int count = Math.min(properties.getFetchPages(), results.size());
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (RankedResult result : results.subList(0, count)) {
            futures.add(CompletableFuture.runAsync(() -> {
                if (System.currentTimeMillis() - startedAt >= properties.getTotalTimeoutMs()) return;
                try {
                    PageContent page = fetchPage(result.url, 0);
                    if (page != null && !page.text().isBlank()) {
                        result.page = page;
                        result.publishedAt = firstNonBlank(result.publishedAt, page.publishedAt());
                        if (result.title.isBlank()) result.title = page.title();
                    }
                } catch (Exception e) {
                    log.debug("Page fetch skipped {}: {}", result.url, e.getMessage());
                }
            }, pageExecutor));
        }
        long remaining = Math.max(1, properties.getTotalTimeoutMs() - 300L
                - (System.currentTimeMillis() - startedAt));
        long pagePhaseBudget = Math.min(remaining, properties.getPageTimeoutMs());
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(pagePhaseBudget, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            futures.forEach(f -> f.cancel(true));
            log.debug("Page fetch phase ended early: {}", e.getMessage());
        }
    }

    private PageContent fetchPage(String url, int redirects) throws Exception {
        PageContent cached = getCached(pageCache, normalizeUrl(url));
        if (cached != null) return cached;
        if (redirects > 3) throw new IllegalArgumentException("too many redirects");
        URI uri = validatePublicUri(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getPageTimeoutMs()))
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.7")
                .header("User-Agent", "Mozilla/5.0 (compatible; AI-Chat-Research/2.0; +http://127.0.0.1)")
                .GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            String location = response.headers().firstValue("location").orElseThrow();
            URI redirected = uri.resolve(location);
            return fetchPage(redirected.toString(), redirects + 1);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (declared > properties.getMaxResponseBytes()) throw new IllegalArgumentException("page too large");
        String contentType = response.headers().firstValue("content-type").orElse("text/html").toLowerCase(Locale.ROOT);
        if (!(contentType.contains("html") || contentType.startsWith("text/plain"))) {
            throw new IllegalArgumentException("unsupported content type");
        }
        byte[] bytes;
        try (InputStream input = response.body()) {
            bytes = readLimited(input, properties.getMaxResponseBytes());
        }
        Document document = Jsoup.parse(new ByteArrayInputStream(bytes), null, uri.toString());
        PageContent page = extractPage(document, uri.toString());
        if (page.text().length() > properties.getMaxPageChars()) {
            page = new PageContent(page.title(), page.url(), page.text().substring(0, properties.getMaxPageChars()), page.publishedAt());
        }
        putCached(pageCache, normalizeUrl(url), page, Duration.ofHours(properties.getPageCacheHours()));
        return page;
    }

    static PageContent extractPage(Document document, String url) {
        document.select("script,style,noscript,svg,canvas,iframe,form,nav,footer,aside,.advertisement,.ads,.cookie,.popup").remove();
        String title = firstNonBlank(document.select("meta[property=og:title]").attr("content"), document.title());
        String published = firstNonBlank(
                document.select("meta[property=article:published_time]").attr("content"),
                document.select("meta[name=date]").attr("content"),
                document.select("time[datetime]").attr("datetime"));
        Element root = firstElement(document.select("article"), document.select("main"),
                document.select("[role=main]"), document.body() == null ? new Elements() : new Elements(document.body()));
        if (root == null) return new PageContent(title, url, "", published);
        List<String> blocks = new ArrayList<>();
        for (Element element : root.select("h1,h2,h3,p,li,pre,blockquote")) {
            String text = element.text().replaceAll("\\s+", " ").strip();
            if (text.length() >= 20 && !isBoilerplate(text)) blocks.add(text);
        }
        String joined = String.join("\n", blocks);
        if (joined.length() > 15000) joined = joined.substring(0, 15000);
        if (published.isBlank()) {
            Matcher matcher = DATE_PATTERN.matcher(joined.substring(0, Math.min(joined.length(), 1200)));
            if (matcher.find()) published = matcher.group(1);
        }
        return new PageContent(title, url, joined, published);
    }

    private List<SearchSource> rerank(String query, List<RankedResult> recalled, int maxSources, long deadline) {
        List<ChunkCandidate> chunks = new ArrayList<>();
        double maxRrf = recalled.stream().mapToDouble(r -> r.rrf).max().orElse(1.0);
        for (RankedResult result : recalled.stream().limit(6).toList()) {
            String material = result.page != null && !result.page.text().isBlank() ? result.page.text() : result.snippet;
            if (material == null || material.isBlank()) continue;
            List<String> pageChunks = split(material, 600, 80);
            pageChunks.stream()
                    .sorted(Comparator.comparingDouble((String c) -> lexicalScore(query, c)).reversed())
                    .limit(2)
                    .forEach(text -> chunks.add(new ChunkCandidate(result, text,
                            lexicalScore(query, text), result.rrf / Math.max(maxRrf, 0.0001))));
        }
        if (chunks.isEmpty()) return List.of();

        try {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException("research deadline reached");
            List<Embedding> embeddings = CompletableFuture.supplyAsync(() -> {
                List<TextSegment> segments = new ArrayList<>();
                segments.add(TextSegment.from(query));
                chunks.forEach(c -> segments.add(TextSegment.from(c.text)));
                return embeddingModel.embedAll(segments).content();
            }, rerankExecutor).get(remaining, TimeUnit.MILLISECONDS);
            int expectedSize = chunks.size() + 1;
            if (embeddings != null && embeddings.size() == expectedSize) {
                Embedding queryEmbedding = embeddings.get(0);
                for (int i = 0; i < chunks.size(); i++) {
                    chunks.get(i).semantic = cosine(queryEmbedding.vector(), embeddings.get(i + 1).vector());
                }
            }
        } catch (Exception e) {
            log.warn("Embedding rerank unavailable, using lexical fallback: {}", e.getMessage());
            chunks.forEach(c -> c.semantic = c.lexical);
        }

        Map<RankedResult, List<ChunkCandidate>> bySource = new HashMap<>();
        for (ChunkCandidate chunk : chunks) bySource.computeIfAbsent(chunk.result, unused -> new ArrayList<>()).add(chunk);
        List<SearchSource> sources = new ArrayList<>();
        for (Map.Entry<RankedResult, List<ChunkCandidate>> entry : bySource.entrySet()) {
            RankedResult result = entry.getKey();
            List<ChunkCandidate> sourceChunks = entry.getValue().stream()
                    .sorted(Comparator.comparingDouble(ChunkCandidate::combined).reversed()).limit(2).toList();
            double semantic = sourceChunks.stream().mapToDouble(c -> c.semantic).max().orElse(0);
            double lexical = sourceChunks.stream().mapToDouble(c -> c.lexical).max().orElse(0);
            if (lexical <= 0 && !OFFICIAL_REQUEST.matcher(query).find()) continue;
            double authority = authorityScore(result.url);
            double freshness = freshnessScore(result.publishedAt);
            double score = 0.55 * semantic + 0.20 * (result.rrf / Math.max(maxRrf, 0.0001))
                    + 0.15 * authority + 0.10 * freshness;
            String preferredDomain = OFFICIAL_REQUEST.matcher(query).find() ? officialDomainFor(query) : "";
            if (!preferredDomain.isBlank()) {
                score += hostMatches(result.url, preferredDomain) ? 0.25 : -0.08;
            }
            sources.add(SearchSource.builder()
                    .title(firstNonBlank(result.title, result.url))
                    .url(result.url)
                    .publishedAt(result.publishedAt)
                    .engine(String.join(",", result.engines))
                    .snippet(result.snippet)
                    .excerpts(sourceChunks.stream().map(c -> c.text).toList())
                    .score(score)
                    .pageRead(result.page != null && !result.page.text().isBlank())
                    .build());
        }
        List<SearchSource> ordered = sources.stream()
                .filter(source -> source.getScore() >= MIN_SOURCE_SCORE)
                .sorted(Comparator.comparingDouble(SearchSource::getScore).reversed()).toList();
        List<SearchSource> selected = new ArrayList<>();
        Map<String, Integer> chunksPerDomain = new HashMap<>();
        int totalChunks = 0;
        for (SearchSource source : ordered) {
            if (selected.size() >= maxSources || totalChunks >= 8) break;
            String domain = domainOf(source.getUrl());
            int domainRemaining = Math.max(0, 2 - chunksPerDomain.getOrDefault(domain, 0));
            int keep = Math.min(Math.min(domainRemaining, 8 - totalChunks), source.getExcerpts().size());
            if (keep <= 0) continue;
            source.setExcerpts(new ArrayList<>(source.getExcerpts().subList(0, keep)));
            selected.add(source);
            chunksPerDomain.merge(domain, keep, Integer::sum);
            totalChunks += keep;
        }
        return selected;
    }

    private String formatContext(String query, List<SearchSource> sources) {
        StringBuilder sb = new StringBuilder("【联网研究结果】\n");
        sb.append("用户问题：").append(query).append("\n");
        sb.append("以下编号是唯一允许引用的联网来源；回答外部事实时使用 [1]、[2] 格式，并保留来源 URL。\n\n");
        int index = 1;
        for (SearchSource source : sources) {
            sb.append("[").append(index++).append("] ").append(source.getTitle()).append("\n");
            sb.append("URL: ").append(source.getUrl()).append("\n");
            if (source.getPublishedAt() != null && !source.getPublishedAt().isBlank()) {
                sb.append("Date: ").append(source.getPublishedAt()).append("\n");
            }
            for (String excerpt : source.getExcerpts()) sb.append("Excerpt: ").append(excerpt).append("\n");
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    private URI buildSearxUri(String query, String language, String timeRange) {
        StringBuilder url = new StringBuilder(trimSlash(properties.getSearxngUrl())).append("/search?q=")
                .append(encode(query)).append("&format=json&categories=general&safesearch=0")
                .append("&language=").append(encode(language));
        if (properties.getEngines() != null && !properties.getEngines().isBlank()) {
            url.append("&engines=").append(encode(properties.getEngines()));
        }
        if (timeRange != null && !timeRange.isBlank()) url.append("&time_range=").append(encode(timeRange));
        return URI.create(url.toString());
    }

    private URI validatePublicUri(String url) throws Exception {
        URI uri = URI.create(url);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("unsupported URL scheme");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || host.equalsIgnoreCase("localhost") || host.endsWith(".local")) {
            throw new IllegalArgumentException("private host rejected");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (!isPublicAddress(address)) throw new IllegalArgumentException("private address rejected");
        }
        return uri;
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            return !(a == 0 || a == 10 || a == 127 || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168) || a >= 224);
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = bytes[0] & 0xff;
            return !(first == 0xfc || first == 0xfd || (first == 0xfe && (bytes[1] & 0xc0) == 0x80));
        }
        return true;
    }

    private String contextualizeFollowUp(String query, List<String> history) {
        if (history == null || history.isEmpty() || !(query.length() < 24 || FOLLOW_UP.matcher(query).matches())) return query;
        for (int i = history.size() - 1; i >= 0; i--) {
            String previous = history.get(i);
            if (previous != null && !previous.isBlank() && !previous.equals(query)) {
                return previous.strip() + " " + query;
            }
        }
        return query;
    }

    private List<String> limitQueries(Set<String> queries) {
        return queries.stream().map(this::sanitizeQuery).filter(s -> !s.isBlank())
                .limit(Math.max(1, properties.getMaxQueries())).toList();
    }

    private String sanitizeQuery(String query) {
        String cleaned = query == null ? "" : query.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").strip();
        return cleaned.length() > 160 ? cleaned.substring(0, 160) : cleaned;
    }

    private String responseJsonText(String body) throws Exception {
        return objectMapper.readTree(body).path("choices").path(0).path("message").path("content").asText(null);
    }

    private static List<String> split(String text, int size, int overlap) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + size);
            if (end < text.length()) {
                int boundary = Math.max(text.lastIndexOf('\n', end), Math.max(text.lastIndexOf('。', end), text.lastIndexOf('.', end)));
                if (boundary > start + size / 2) end = boundary + 1;
            }
            String chunk = text.substring(start, end).strip();
            if (!chunk.isBlank()) out.add(chunk);
            if (end >= text.length()) break;
            start = Math.max(start + 1, end - overlap);
        }
        return out;
    }

    private static double lexicalScore(String query, String text) {
        if (query == null || text == null) return 0;
        String haystack = text.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>(List.of(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        String chinese = query.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i + 2 <= chinese.length() && terms.size() < 24; i += 2) terms.add(chinese.substring(i, i + 2));
        long matched = terms.stream().filter(t -> t.length() >= 2 && haystack.contains(t)).distinct().count();
        return Math.min(1.0, matched / (double) Math.max(1, terms.stream().filter(t -> t.length() >= 2).count()));
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return Math.max(0, Math.min(1, dot / (Math.sqrt(na) * Math.sqrt(nb))));
    }

    private static double rrfContribution(JsonNode item, int fallbackRank) {
        JsonNode positions = item.path("positions");
        if (!positions.isArray() || positions.isEmpty()) return 1.0 / (RRF_K + fallbackRank);
        double score = 0;
        for (JsonNode position : positions) {
            int rank = Math.max(1, position.asInt(fallbackRank));
            score += 1.0 / (RRF_K + rank);
        }
        return score;
    }

    private static double authorityScore(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.matches("https?://[^/]*(gov|edu)\\.[^/]+.*") || lower.contains("github.com/")
                || lower.contains("docs.") || lower.contains("developer.")) return 1.0;
        if (lower.contains("wikipedia.org") || lower.matches("https?://[^/]+\\.org/.*")) return 0.8;
        if (lower.contains("baijiahao.baidu.com") || lower.contains("csdn.net")) return 0.35;
        return 0.6;
    }

    private static String officialDomainFor(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (lower.contains("spring boot") || lower.contains("spring框架") || lower.contains("spring 文档")) return "docs.spring.io";
        if (lower.contains("openai") || lower.contains("chatgpt") || lower.contains("gpt")) return "developers.openai.com";
        if (lower.contains("claude") || lower.contains("anthropic")) return "anthropic.com";
        if (lower.contains("gemini") || lower.contains("google")) return "google.com";
        if (lower.contains("docker")) return "docs.docker.com";
        if (lower.contains("github")) return "docs.github.com";
        if (lower.contains("react")) return "react.dev";
        if (lower.contains("vue")) return containsChinese(query) ? "cn.vuejs.org" : "vuejs.org";
        if (lower.contains("mysql")) return "dev.mysql.com";
        if (lower.contains("java")) return "docs.oracle.com";
        if (lower.contains("microsoft") || lower.contains("windows") || lower.contains("微软")) return "learn.microsoft.com";
        if (lower.contains("apple") || lower.contains("swift") || lower.contains("ios") || lower.contains("macos") || lower.contains("苹果")) return "developer.apple.com";
        return "";
    }

    private static boolean hostMatches(String url, String domain) {
        try {
            String host = URI.create(url).getHost();
            return host != null && (host.equalsIgnoreCase(domain)
                    || host.toLowerCase(Locale.ROOT).endsWith("." + domain.toLowerCase(Locale.ROOT))
                    || domain.toLowerCase(Locale.ROOT).endsWith("." + host.toLowerCase(Locale.ROOT)));
        } catch (Exception e) {
            return false;
        }
    }

    private static double freshnessScore(String publishedAt) {
        if (publishedAt == null || publishedAt.isBlank()) return 0.45;
        Matcher matcher = Pattern.compile("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})").matcher(publishedAt);
        if (!matcher.find()) return 0.55;
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
            long days = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(date, LocalDate.now()));
            if (days <= 7) return 1.0;
            if (days <= 30) return 0.85;
            if (days <= 365) return 0.65;
            return 0.35;
        } catch (Exception ignored) {
            return 0.5;
        }
    }

    private static byte[] readLimited(InputStream input, int max) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(max, 65536));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > max) throw new IllegalArgumentException("page exceeds size limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean isBoilerplate(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.matches("^(登录|注册|首页|菜单|导航|广告|隐私|cookie|copyright|all rights reserved).*")
                || (text.length() < 80 && (lower.contains("扫码") || lower.contains("关注我们") || lower.contains("点击下载")));
    }

    private static Element firstElement(Elements... groups) {
        for (Elements elements : groups) if (elements != null && !elements.isEmpty()) return elements.first();
        return null;
    }

    private static boolean isPublicHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null;
        } catch (Exception e) { return false; }
    }

    private static String normalizeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath().replaceAll("/$", "");
            return (uri.getScheme() + "://" + uri.getHost() + path).toLowerCase(Locale.ROOT);
        } catch (Exception e) { return url.replaceFirst("[?#].*$", "").toLowerCase(Locale.ROOT); }
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "").strip();
    }

    private static String domainOf(String url) {
        try { return URI.create(url).getHost().toLowerCase(Locale.ROOT); }
        catch (Exception e) { return normalizeUrl(url); }
    }

    private List<String> configuredEngines() {
        if (properties.getEngines() == null) return List.of();
        return List.of(properties.getEngines().split(",")).stream()
                .map(String::strip).filter(s -> !s.isBlank()).toList();
    }

    private static String normalizeTimeRange(String value) {
        return Set.of("day", "week", "month", "year").contains(value) ? value : "";
    }

    private static boolean containsChinese(String text) { return text != null && text.matches(".*[\\u4e00-\\u9fa5].*"); }
    private static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    private static String trimSlash(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
    private static String abbreviate(String value) { return value.length() > 80 ? value.substring(0, 80) + "..." : value; }
    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.strip();
        return "";
    }

    private String cacheKey(SearchRequest request, String query) {
        String history = request.getConversationContext() == null ? "" : String.join("|", request.getConversationContext());
        return query.toLowerCase(Locale.ROOT) + "|" + history.hashCode() + "|" + request.getLanguage()
                + "|" + request.getTimeRange() + "|" + request.getMaxSources() + "|" + properties.getEngines();
    }

    private static <T> T getCached(Map<String, CacheEntry<T>> cache, String key) {
        CacheEntry<T> entry = cache.get(key);
        if (entry == null) return null;
        if (entry.expiresAt < System.currentTimeMillis()) { cache.remove(key); return null; }
        return entry.value;
    }

    private static <T> void putCached(Map<String, CacheEntry<T>> cache, String key, T value, Duration ttl) {
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis() + Math.max(1000, ttl.toMillis())));
    }

    private SearchResponse empty(String status, List<String> planned, String message, long startedAt) {
        return SearchResponse.builder().status(status).plannedQueries(planned).sources(List.of())
                .contextText("【联网检索状态】" + message + "。不要编造来源或声称已经查到最新信息。")
                .diagnostics(Map.of("costMs", System.currentTimeMillis() - startedAt)).build();
    }

    private static void notify(SearchRequest request, SearchProgress progress) {
        Consumer<SearchProgress> listener = request == null ? null : request.getProgressListener();
        if (listener != null) {
            try { listener.accept(progress); } catch (Exception ignored) { }
        }
    }

    record QueryPlan(List<String> queries, String language, String timeRange) { }
    private record CacheEntry<T>(T value, long expiresAt) { }
    static record PageContent(String title, String url, String text, String publishedAt) { }

    private static final class RankedResult {
        private String title;
        private final String url;
        private String snippet;
        private String publishedAt = "";
        private final Set<String> engines = new LinkedHashSet<>();
        private double rrf;
        private PageContent page;
        private RankedResult(String title, String url, String snippet) {
            this.title = title; this.url = url; this.snippet = snippet;
        }
    }

    private static final class ChunkCandidate {
        private final RankedResult result;
        private final String text;
        private final double lexical;
        private final double normalizedRrf;
        private double semantic;
        private ChunkCandidate(RankedResult result, String text, double lexical, double normalizedRrf) {
            this.result = result; this.text = text; this.lexical = lexical; this.normalizedRrf = normalizedRrf;
            this.semantic = lexical;
        }
        private double combined() { return 0.75 * semantic + 0.25 * normalizedRrf; }
    }
}
