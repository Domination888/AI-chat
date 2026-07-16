package org.example.searxngmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local MCP server that proxies web search to SearXNG.
 * JSON-RPC 2.0 over stdio, one message per line.
 */
public class SearxngMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL_NAME = "webSearch";
    private static final int DEFAULT_MAX_RESULTS = 8;
    private static final int DEFAULT_TIMEOUT_MS = 8000;
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");
    private static final Pattern FRESHNESS_QUERY = Pattern.compile(".*(最新|最近|今天|今日|当前|现在|新闻|公告|发布|更新|价格|股价|汇率|赛程|比分|202\\d).*");

    private static String searxngUrl = "http://localhost:8080";
    private static int timeoutMs = DEFAULT_TIMEOUT_MS;
    private static HttpClient httpClient;

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        String envUrl = System.getenv("SEARXNG_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            searxngUrl = envUrl.trim();
        }
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        System.err.println("[SearxngMcpServer] started, searxngUrl=" + searxngUrl);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            try {
                JsonNode msg = MAPPER.readTree(line);
                String response = handle(msg);
                if (response != null) {
                    System.out.println(response);
                    System.out.flush();
                }
            } catch (Exception e) {
                System.err.println("[SearxngMcpServer] error: " + e.getMessage());
            }
        }
        System.err.println("[SearxngMcpServer] closed");
    }

    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--searxng-url".equals(arg) && i + 1 < args.length) {
                searxngUrl = args[++i];
            } else if ("--timeout-ms".equals(arg) && i + 1 < args.length) {
                try {
                    timeoutMs = Integer.parseInt(args[++i]);
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
        }
    }

    private static String handle(JsonNode msg) throws Exception {
        if (!msg.has("id") || msg.get("id").isNull()) {
            return null;
        }

        JsonNode id = msg.get("id");
        String method = msg.has("method") ? msg.get("method").asText("") : "";
        JsonNode params = msg.has("params") ? msg.get("params") : MAPPER.createObjectNode();

        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);

        switch (method) {
            case "initialize" -> {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("protocolVersion", "2024-11-05");
                ObjectNode caps = MAPPER.createObjectNode();
                caps.set("tools", MAPPER.createObjectNode());
                result.set("capabilities", caps);
                ObjectNode info = MAPPER.createObjectNode();
                info.put("name", "searxng-search");
                info.put("version", "1.0.0");
                result.set("serverInfo", info);
                response.set("result", result);
            }
            case "tools/list" -> response.set("result", listTools());
            case "tools/call" -> response.set("result", callTool(params));
            case "ping" -> response.set("result", MAPPER.createObjectNode());
            default -> {
                ObjectNode error = MAPPER.createObjectNode();
                error.put("code", -32601);
                error.put("message", "Method not found: " + method);
                response.set("error", error);
            }
        }

        return MAPPER.writeValueAsString(response);
    }

    private static ObjectNode listTools() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();

        ObjectNode queryProp = MAPPER.createObjectNode();
        queryProp.put("type", "string");
        queryProp.put("description", "Search query");
        props.set("query", queryProp);

        ObjectNode langProp = MAPPER.createObjectNode();
        langProp.put("type", "string");
        langProp.put("description", "Search language, for example zh-CN or en-US (optional)");
        props.set("language", langProp);

        ObjectNode categoryProp = MAPPER.createObjectNode();
        categoryProp.put("type", "string");
        categoryProp.put("description", "Search categories (optional)");
        props.set("categories", categoryProp);

        ObjectNode maxProp = MAPPER.createObjectNode();
        maxProp.put("type", "integer");
        maxProp.put("description", "Max results (optional)");
        props.set("max_results", maxProp);

        ObjectNode timeProp = MAPPER.createObjectNode();
        timeProp.put("type", "string");
        timeProp.put("description", "SearXNG time range: day, week, month, year (optional)");
        props.set("time_range", timeProp);

        schema.set("properties", props);
        ArrayNode required = MAPPER.createArrayNode();
        required.add("query");
        schema.set("required", required);
        schema.put("additionalProperties", false);

        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Search the web via SearXNG");
        tool.set("inputSchema", schema);

        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", MAPPER.createArrayNode().add(tool));
        return result;
    }

    private static ObjectNode callTool(JsonNode params) {
        String toolName = params.has("name") ? params.get("name").asText() : "";
        JsonNode arguments = params.has("arguments") ? params.get("arguments") : MAPPER.createObjectNode();

        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = MAPPER.createArrayNode();

        if (!TOOL_NAME.equals(toolName)) {
            content.add(MAPPER.createObjectNode().put("type", "text").put("text", "Unknown tool: " + toolName));
            result.put("isError", true);
            result.set("content", content);
            return result;
        }

        String query = readArg(arguments, "query");
        if (query.isEmpty()) {
            query = readArg(arguments, "search_query");
        }
        String language = readArg(arguments, "language");
        String categories = readArg(arguments, "categories");
        String timeRange = readArg(arguments, "time_range");
        int maxResults = readIntArg(arguments, "max_results", DEFAULT_MAX_RESULTS);

        if (query.isEmpty()) {
            content.add(MAPPER.createObjectNode().put("type", "text").put("text", "Missing query"));
            result.put("isError", true);
            result.set("content", content);
            return result;
        }

        try {
            String text = doSearch(query, language, categories, timeRange, maxResults);
            content.add(MAPPER.createObjectNode().put("type", "text").put("text", text));
            result.put("isError", false);
        } catch (Exception e) {
            content.add(MAPPER.createObjectNode().put("type", "text").put("text", "Search failed: " + e.getMessage()));
            result.put("isError", true);
        }

        result.set("content", content);
        return result;
    }

    private static String readArg(JsonNode args, String key) {
        return args.has(key) ? args.get(key).asText("") : "";
    }

    private static int readIntArg(JsonNode args, String key, int fallback) {
        if (!args.has(key)) return fallback;
        try {
            return args.get(key).asInt(fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String doSearch(String query, String language, String categories, String timeRange, int maxResults) throws Exception {
        int cappedMax = Math.max(1, Math.min(maxResults, 12));
        String effectiveLanguage = language == null || language.isBlank() ? guessLanguage(query) : language;
        String effectiveTimeRange = timeRange == null || timeRange.isBlank() ? defaultTimeRange(query) : timeRange;

        List<SearchResult> collected = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        for (String candidateQuery : expandQueries(query)) {
            collect(candidateQuery, effectiveLanguage, categories, effectiveTimeRange, collected, seenUrls, seenTitles);
            if (collected.size() >= cappedMax * 3) {
                break;
            }
        }

        if (collected.isEmpty()) {
            return "No results";
        }

        List<String> queryTerms = queryTerms(query);
        String officialDomain = officialDomainFor(query);
        collected.sort(Comparator
                .comparingDouble((SearchResult r) -> score(r, queryTerms, officialDomain))
                .reversed()
                .thenComparingInt(r -> r.rank));

        StringBuilder sb = new StringBuilder();
        sb.append("Search query: ").append(query).append("\n");
        sb.append("Language: ").append(effectiveLanguage);
        if (effectiveTimeRange != null && !effectiveTimeRange.isBlank()) {
            sb.append(", time_range: ").append(effectiveTimeRange);
        }
        sb.append("\n\n");

        int count = 0;
        for (SearchResult item : collected) {
            if (count >= cappedMax) break;
            count++;
            sb.append(count).append(". ").append(item.title).append("\n");
            sb.append("URL: ").append(item.url).append("\n");
            if (!item.engine.isBlank()) {
                sb.append("Engine: ").append(item.engine).append("\n");
            }
            if (!item.publishedDate.isBlank()) {
                sb.append("Date: ").append(item.publishedDate).append("\n");
            }
            if (!item.content.isBlank()) {
                sb.append("Snippet: ").append(item.content).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private static void collect(String query, String language, String categories, String timeRange,
                                List<SearchResult> collected, Set<String> seenUrls, Set<String> seenTitles) throws Exception {
        String url = buildSearchUrl(query, language, categories, timeRange);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json")
                .header("User-Agent", "AI-Chat-SearXNG-MCP/1.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("SearXNG HTTP " + response.statusCode());
        }

        JsonNode results = MAPPER.readTree(response.body()).path("results");
        if (!results.isArray()) {
            return;
        }

        int rank = 0;
        for (JsonNode item : results) {
            rank++;
            SearchResult result = SearchResult.from(item, rank);
            if (!result.isUsable()) {
                continue;
            }
            String normalizedUrl = normalizeUrl(result.url);
            String normalizedTitle = normalizeTitle(result.title);
            if (!seenUrls.add(normalizedUrl) || !seenTitles.add(normalizedTitle)) {
                continue;
            }
            collected.add(result);
        }
    }

    private static String buildSearchUrl(String query, String language, String categories, String timeRange) {
        String base = searxngUrl.endsWith("/") ? searxngUrl.substring(0, searxngUrl.length() - 1) : searxngUrl;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("format", "json");
        params.put("safesearch", "0");
        if (language != null && !language.isBlank()) {
            params.put("language", language);
        } else {
            params.put("language", guessLanguage(query));
        }
        if (categories != null && !categories.isBlank()) {
            params.put("categories", categories);
        } else {
            params.put("categories", "general");
        }
        if (timeRange != null && !timeRange.isBlank()) {
            params.put("time_range", timeRange);
        }

        StringBuilder sb = new StringBuilder(base).append("/search?");
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) sb.append("&");
            first = false;
            sb.append(entry.getKey())
              .append("=")
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static List<String> expandQueries(String query) {
        String cleaned = query.trim().replaceAll("\\s+", " ");
        List<String> queries = new ArrayList<>();
        queries.add(cleaned);
        String officialDomain = officialDomainFor(cleaned);
        if (!officialDomain.isBlank() && !cleaned.contains("site:")) {
            queries.add(cleaned + " site:" + officialDomain);
        }
        if (FRESHNESS_QUERY.matcher(cleaned).matches() && !cleaned.contains("官网")) {
            queries.add(cleaned + " 官网 OR 公告 OR 新闻");
        }
        if (CHINESE.matcher(cleaned).find() && !cleaned.matches(".*(最新|今天|今日|当前|现在|202\\d).*")) {
            queries.add(cleaned + " 最新");
        }
        return queries.stream().distinct().toList();
    }

    private static String officialDomainFor(String query) {
        String lower = query.toLowerCase();
        if (lower.contains("openai") || lower.contains("chatgpt") || lower.contains("gpt")) return "openai.com";
        if (lower.contains("claude") || lower.contains("anthropic")) return "anthropic.com";
        if (lower.contains("gemini") || lower.contains("google")) return "google.com";
        if (lower.contains("deepseek")) return "deepseek.com";
        if (lower.contains("qwen") || query.contains("通义") || query.contains("千问")) return "qwenlm.github.io";
        if (lower.contains("docker")) return "docker.com";
        if (lower.contains("spring")) return "spring.io";
        if (lower.contains("java")) return "oracle.com";
        if (lower.contains("github")) return "github.com";
        if (lower.contains("npm")) return "npmjs.com";
        if (lower.contains("maven")) return "mvnrepository.com";
        if (query.contains("苹果") || lower.contains("apple") || lower.contains("ios") || lower.contains("macos")) return "apple.com";
        if (query.contains("微软") || lower.contains("microsoft") || lower.contains("windows")) return "microsoft.com";
        if (query.contains("英伟达") || lower.contains("nvidia")) return "nvidia.com";
        if (query.contains("小米")) return "mi.com";
        if (query.contains("华为")) return "huawei.com";
        return "";
    }

    private static String guessLanguage(String query) {
        return CHINESE.matcher(query).find() ? "zh-CN" : "en-US";
    }

    private static String defaultTimeRange(String query) {
        return FRESHNESS_QUERY.matcher(query).matches() ? "month" : "";
    }

    private static List<String> queryTerms(String query) {
        String[] parts = query.toLowerCase().replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+", " ").trim().split("\\s+");
        List<String> terms = new ArrayList<>();
        for (String part : parts) {
            if (part.length() >= 2 && !part.matches("(最新|今天|今日|当前|现在|搜索|查询|一下)")) {
                terms.add(part);
            }
        }
        return terms;
    }

    private static double score(SearchResult result, List<String> queryTerms, String officialDomain) {
        String haystack = (result.title + " " + result.content + " " + result.url).toLowerCase();
        double score = 0;
        for (String term : queryTerms) {
            if (haystack.contains(term.toLowerCase())) {
                score += 2.0;
            }
        }
        if (!result.title.isBlank()) score += 1.5;
        if (!result.content.isBlank()) score += Math.min(2.0, result.content.length() / 120.0);
        if (!result.publishedDate.isBlank()) score += 1.0;
        if (!officialDomain.isBlank() && result.url.toLowerCase().contains("://" + officialDomain)) {
            score += 5.0;
        }
        if (result.url.matches("https?://([^/]+\\.)?(gov|edu|org|github|wikipedia|docs|openai|microsoft|apple|google|nvidia|spring|docker)\\.[^/]+.*")) {
            score += 1.0;
        }
        if (result.url.contains("zhihu.com/question") || result.url.contains("baijiahao.baidu.com")) {
            score -= 1.0;
        }
        score += Math.max(0, 1.0 - result.rank * 0.03);
        return score;
    }

    private static String normalizeUrl(String url) {
        return url.replaceFirst("[?#].*$", "").replaceAll("/$", "").toLowerCase();
    }

    private static String normalizeTitle(String title) {
        return title.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private record SearchResult(String title, String url, String content, String engine, String publishedDate, int rank) {
        static SearchResult from(JsonNode item, int rank) {
            String title = item.path("title").asText("").trim();
            String url = item.path("url").asText("").trim();
            String content = item.path("content").asText("").replaceAll("\\s+", " ").trim();
            String engine = item.path("engine").asText("").trim();
            String publishedDate = item.path("publishedDate").asText("").trim();
            if (publishedDate.isBlank()) {
                publishedDate = item.path("published_date").asText("").trim();
            }
            return new SearchResult(title, url, content, engine, publishedDate, rank);
        }

        boolean isUsable() {
            return !url.isBlank() && (!title.isBlank() || !content.isBlank());
        }
    }
}
