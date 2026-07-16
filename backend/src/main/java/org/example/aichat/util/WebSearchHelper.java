package org.example.aichat.util;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索前的查询优化：避免把整句口语/追问直接丢给 SearXNG，并尽量为天气类问题补充结构化实况。
 */
public final class WebSearchHelper {

    private static final Pattern WEATHER_INTENT = Pattern.compile("天气|气温|温度|几度|下雨|下雪|刮风|预报");
    private static final Pattern CITY = cityPattern();
    private static final Pattern VAGUE_WEATHER = Pattern.compile("^(天气|气温|温度|几度|具体天气|天气情况|天气如何)$");
    private static final Pattern STRONG_WEB_INTENT = Pattern.compile(
            "(联网|搜索|查(一下|下|找|询)?|搜(一下|下)?|最新|最近|现在|当前|目前|今天|今日|昨日|昨天|明天|本周|本月|今年|"
                    + "新闻|热搜|公告|发布|更新|版本|价格|股价|汇率|票房|赛程|比分|排名|政策|法规|规定|官网|链接|网址|下载|"
                    + "论文|报告|数据|统计|榜单|参数|配置|评测|对比|是否还|有没有|什么时候)");
    private static final Pattern EXTERNAL_FACT_HINT = Pattern.compile(
            "(OpenAI|ChatGPT|Claude|Gemini|GPT|Qwen|DeepSeek|Llama|Android|iOS|Windows|macOS|Java|Spring|Vue|React|Docker|"
                    + "英伟达|苹果|微软|谷歌|特斯拉|小米|华为|字节|阿里|腾讯|B站|微博|知乎|GitHub|npm|Maven|PyPI|Steam|"
                    + "电影|电视剧|游戏|会议|展会|考试|航班|高铁|机票|酒店|门票|股票|基金|加密货币)");

    private static final Map<String, String> CITY_WTTR = new LinkedHashMap<>();

    static {
        CITY_WTTR.put("北京", "Beijing");
        CITY_WTTR.put("上海", "Shanghai");
        CITY_WTTR.put("广州", "Guangzhou");
        CITY_WTTR.put("深圳", "Shenzhen");
        CITY_WTTR.put("杭州", "Hangzhou");
        CITY_WTTR.put("南京", "Nanjing");
        CITY_WTTR.put("成都", "Chengdu");
        CITY_WTTR.put("重庆", "Chongqing");
        CITY_WTTR.put("武汉", "Wuhan");
        CITY_WTTR.put("西安", "Xi'an");
        CITY_WTTR.put("天津", "Tianjin");
        CITY_WTTR.put("香港", "Hong+Kong");
        CITY_WTTR.put("台北", "Taipei");
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static Pattern cityPattern() {
        return Pattern.compile(
                "(北京|上海|广州|深圳|杭州|南京|成都|重庆|武汉|西安|天津|苏州|青岛|大连|厦门|福州|长沙|郑州|济南|合肥|昆明|贵阳|南宁|海口|三亚|哈尔滨|沈阳|长春|石家庄|太原|南昌|兰州|乌鲁木齐|拉萨|银川|西宁|呼和浩特|香港|澳门|台北)");
    }

    private WebSearchHelper() {
    }

    /** 天气类意图（供技能与通用搜索分流）。 */
    public static boolean isWeatherIntent(String message) {
        return message != null && WEATHER_INTENT.matcher(message).find();
    }

    /** 是否值得在请求入口做一次预搜索（过泛的追问应跳过，交给模型结合上下文调工具）。 */
    public static boolean shouldPreSearch(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String optimized = optimizeQuery(message);
        if (optimized.length() < 4) {
            return false;
        }
        if (VAGUE_WEATHER.matcher(optimized).matches()) {
            return false;
        }
        // 纯追问、无实体：如「告诉我具体天气情况如何」
        if (WEATHER_INTENT.matcher(message).find() && !CITY.matcher(message).find()
                && optimized.length() < 12) {
            return false;
        }
        if (STRONG_WEB_INTENT.matcher(message).find()) {
            return true;
        }
        if (EXTERNAL_FACT_HINT.matcher(message).find() && optimized.length() >= 4) {
            return true;
        }
        return looksLikeConcreteQuestion(optimized);
    }

    /**
     * 将用户原话转为更适合元搜索的关键词。
     * 天气类尽量规范为「{城市} 天气 今天」。
     */
    public static String optimizeQuery(String message) {
        if (message == null) {
            return "";
        }
        String q = message.trim()
                .replaceAll("[？?。！!，,、]", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("(怎么样|如何|吗|呢|请|告诉|查询|搜索|帮我|我想知道|想知道|具体|到底|情况|怎样)", "")
                .trim();

        Matcher cityMatcher = CITY.matcher(message);
        String city = cityMatcher.find() ? cityMatcher.group(1) : null;

        if (WEATHER_INTENT.matcher(message).find()) {
            if (city != null) {
                return city + " 天气 今天";
            }
            if (!q.contains("天气")) {
                q = q + " 天气";
            }
            if (!q.contains("今天") && !q.contains("明天") && !q.contains("实时")) {
                q = q + " 今天";
            }
        }

        if (STRONG_WEB_INTENT.matcher(message).find()
                && !q.matches(".*(最新|今天|今日|当前|现在|202\\d|新闻|价格|版本|官网|公告).*")) {
            q = q + " 最新";
        }

        return q.trim();
    }

    private static boolean looksLikeConcreteQuestion(String optimized) {
        if (optimized.length() < 8) {
            return false;
        }
        if (optimized.matches(".*(是什么|是谁|哪里|哪个|多少|为什么|怎么|如何|区别|对比|原因|时间|日期|地址|官网|下载).*")) {
            return true;
        }
        return optimized.matches(".*[A-Za-z0-9].*") && optimized.length() >= 6;
    }

    /**
     * 天气类问题：用 wttr.in 拉一条结构化实况，拼在 SearXNG 结果前（失败则忽略）。
     */
    public static String weatherSnapshot(String message) {
        if (message == null || !WEATHER_INTENT.matcher(message).find()) {
            return null;
        }
        Matcher m = CITY.matcher(message);
        if (!m.find()) {
            return null;
        }
        return weatherSnapshotForCity(m.group(1));
    }

    public static String weatherSnapshotForCity(String cityCn) {
        if (cityCn == null || cityCn.isBlank()) {
            return null;
        }
        String wttrLoc = CITY_WTTR.getOrDefault(cityCn, cityCn);
        try {
            String encoded = URLEncoder.encode(wttrLoc, StandardCharsets.UTF_8);
            String url = "https://wttr.in/" + encoded + "?format=j1&lang=zh";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "curl/8.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
                return null;
            }
            return parseWttrJson(cityCn, resp.body());
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseWttrJson(String cityCn, String json) {
        // 轻量解析，避免引入额外依赖
        String temp = extractJsonString(json, "\"temp_C\"\\s*:\\s*\"([^\"]+)\"");
        if (temp == null) {
            temp = extractJsonString(json, "\"temp_C\"\\s*:\\s*(\\d+)");
        }
        String desc = extractJsonString(json, "\"lang_zh\"\\s*:\\s*\\[\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");
        if (desc == null) {
            desc = extractJsonString(json, "\"weatherDesc\"\\s*:\\s*\\[\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");
        }
        String humidity = extractJsonString(json, "\"humidity\"\\s*:\\s*\"([^\"]+)\"");
        if (temp == null && desc == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("【天气实况 · ").append(cityCn).append("】\n");
        if (desc != null) {
            sb.append("现象：").append(desc).append("\n");
        }
        if (temp != null) {
            sb.append("气温：").append(temp).append("°C\n");
        }
        if (humidity != null) {
            sb.append("湿度：").append(humidity).append("%\n");
        }
        sb.append("来源：https://wttr.in/").append(cityCn);
        return sb.toString().trim();
    }

    private static String extractJsonString(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** 通用联网预搜索上下文（不含天气实况，天气由 weather-lookup 技能负责）。 */
    public static String buildSearchContext(String searxResult) {
        if (searxResult == null || searxResult.isBlank()) {
            return null;
        }
        return searxResult.trim();
    }
}
