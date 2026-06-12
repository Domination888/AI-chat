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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local MCP server that proxies web search to SearXNG.
 * JSON-RPC 2.0 over stdio, one message per line.
 */
public class SearxngMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL_NAME = "webSearch";
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int DEFAULT_TIMEOUT_MS = 8000;

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
        langProp.put("description", "Search language (optional)");
        props.set("language", langProp);

        ObjectNode categoryProp = MAPPER.createObjectNode();
        categoryProp.put("type", "string");
        categoryProp.put("description", "Search categories (optional)");
        props.set("categories", categoryProp);

        ObjectNode maxProp = MAPPER.createObjectNode();
        maxProp.put("type", "integer");
        maxProp.put("description", "Max results (optional)");
        props.set("max_results", maxProp);

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
        int maxResults = readIntArg(arguments, "max_results", DEFAULT_MAX_RESULTS);

        if (query.isEmpty()) {
            content.add(MAPPER.createObjectNode().put("type", "text").put("text", "Missing query"));
            result.put("isError", true);
            result.set("content", content);
            return result;
        }

        try {
            String text = doSearch(query, language, categories, maxResults);
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

    private static String doSearch(String query, String language, String categories, int maxResults) throws Exception {
        String url = buildSearchUrl(query, language, categories);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("SearXNG HTTP " + response.statusCode());
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return "No results";
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (JsonNode item : results) {
            if (count >= maxResults) break;
            String title = item.path("title").asText("");
            String itemUrl = item.path("url").asText("");
            String content = item.path("content").asText("");

            if (title.isEmpty() && itemUrl.isEmpty() && content.isEmpty()) {
                continue;
            }
            count++;
            if (!title.isEmpty()) {
                sb.append(count).append(". ").append(title).append("\n");
            }
            if (!itemUrl.isEmpty()) {
                sb.append(itemUrl).append("\n");
            }
            if (!content.isEmpty()) {
                sb.append(content).append("\n");
            }
            sb.append("\n");
        }

        if (sb.length() == 0) {
            return "No results";
        }
        return sb.toString().trim();
    }

    private static String buildSearchUrl(String query, String language, String categories) {
        String base = searxngUrl.endsWith("/") ? searxngUrl.substring(0, searxngUrl.length() - 1) : searxngUrl;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("format", "json");
        if (language != null && !language.isBlank()) {
            params.put("language", language);
        } else {
            params.put("language", "zh-CN");
        }
        if (categories != null && !categories.isBlank()) {
            params.put("categories", categories);
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
}
