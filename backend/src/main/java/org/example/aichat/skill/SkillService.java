package org.example.aichat.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.example.aichat.mcp.AppPaths;
import org.example.aichat.mcp.McpClientManager;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 技能（manifest 式）的加载、持久化与提示词组装。
 *
 * 每个技能是 config/skills/&lt;dir&gt;/ 下的一个 SKILL.md（YAML frontmatter + 正文）。
 * 启用的技能会把"名称 + 说明 + 正文指令 + 绑定工具"注入到系统提示，
 * 让模型懂得在合适时机运用对应能力与 MCP 工具。
 */
@Slf4j
@Service
public class SkillService {

    private final AppPaths appPaths;

    public SkillService(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    @PostConstruct
    public void init() {
        Path dir = appPaths.skillsDir();
        try {
            Files.createDirectories(dir);
            if (isEmptyDir(dir)) {
                log.info("技能目录为空，写入示例技能 web-research: {}", dir);
                seedSample();
            }
            seedWeatherIfMissing();
            seedPrimeCheckIfMissing();
            seedAiDailyIfMissing();
        } catch (IOException e) {
            log.warn("初始化技能目录失败: {}", e.getMessage());
        }
    }

    private boolean isEmptyDir(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    // ----------------------------------------------------------------- CRUD

    public List<SkillManifest> list() {
        Path root = appPaths.skillsDir();
        List<SkillManifest> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path md = dir.resolve("SKILL.md");
                if (Files.isRegularFile(md)) {
                    try {
                        SkillManifest m = parse(Files.readString(md, StandardCharsets.UTF_8));
                        m.setDirName(dir.getFileName().toString());
                        if (m.getName() == null || m.getName().isBlank()) {
                            m.setName(m.getDirName());
                        }
                        result.add(m);
                    } catch (Exception e) {
                        log.warn("解析技能失败 {}: {}", md, e.getMessage());
                    }
                }
            });
        } catch (IOException e) {
            log.warn("列举技能失败: {}", e.getMessage());
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    public Optional<SkillManifest> get(String name) {
        return list().stream().filter(s -> s.getName().equalsIgnoreCase(name)
                || name.equals(s.getDirName())).findFirst();
    }

    public SkillManifest save(SkillManifest manifest) {
        if (manifest.getName() == null || manifest.getName().isBlank()) {
            throw new IllegalArgumentException("技能 name 不能为空");
        }
        String dirName = manifest.getDirName() != null && !manifest.getDirName().isBlank()
                ? manifest.getDirName() : slug(manifest.getName());
        manifest.setDirName(dirName);
        Path dir = appPaths.skillsDir().resolve(dirName);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), render(manifest), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("保存技能失败: " + e.getMessage(), e);
        }
        return manifest;
    }

    public boolean delete(String name) {
        Optional<SkillManifest> target = get(name);
        if (target.isEmpty()) return false;
        Path dir = appPaths.skillsDir().resolve(target.get().getDirName());
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
            return true;
        } catch (IOException e) {
            log.warn("删除技能失败 {}: {}", name, e.getMessage());
            return false;
        }
    }

    public boolean toggle(String name, boolean enabled) {
        Optional<SkillManifest> target = get(name);
        if (target.isEmpty()) return false;
        SkillManifest m = target.get();
        m.setEnabled(enabled);
        save(m);
        return true;
    }

    // -------------------------------------------------------- prompt 装配

    public List<SkillManifest> enabledSkills() {
        return list().stream().filter(SkillManifest::isEnabled).toList();
    }

    /**
     * 组装【能力层】系统提示：技能说明 + MCP 工具目录；两者皆空时返回空串。
     */
    public String buildCapabilityPromptSection(McpClientManager mcp) {
        List<SkillManifest> enabled = enabledSkills();
        List<ToolSpecification> tools = mcp != null ? mcp.listAllTools() : List.of();
        if (enabled.isEmpty() && tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【能力层 · 技能与工具】\n");
        sb.append("当用户问题需要外部数据、精确计算或查证时，必须先调用下列工具获取结果，再进入【角色层】口吻作答。\n");
        sb.append("工具返回的具体数据（数字、判断结果、链接）不可被角色比喻或模糊文风替代。\n\n");

        if (!enabled.isEmpty()) {
            sb.append("【可用技能】\n");
            for (SkillManifest s : enabled) {
                sb.append("\n## ").append(s.getName());
                if (s.getDescription() != null && !s.getDescription().isBlank()) {
                    sb.append(" — ").append(s.getDescription().trim());
                }
                sb.append("\n");
                if (s.getMcpTools() != null && !s.getMcpTools().isEmpty()) {
                    sb.append("工具：").append(String.join(", ", s.getMcpTools())).append("\n");
                }
                if (s.getInstructions() != null && !s.getInstructions().isBlank()) {
                    sb.append(normalizeInstructionHeadings(s.getInstructions().trim())).append("\n");
                }
            }
        }

        if (mcp != null) {
            String catalog = mcp.buildToolsCatalogSection();
            if (!catalog.isBlank()) {
                if (!enabled.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(catalog.trim()).append("\n");
            }
        }
        return sb.toString();
    }

    /** SKILL.md 正文应用 ### 小节；兼容旧文件里误用的 ##。 */
    static String normalizeInstructionHeadings(String instructions) {
        return instructions.replaceAll("(?m)^## ", "### ");
    }

    // ------------------------------------------------------------ 解析/序列化

    @SuppressWarnings("unchecked")
    SkillManifest parse(String content) {
        SkillManifest m = new SkillManifest();
        String body = content;
        String fm = null;

        String trimmed = content.stripLeading();
        if (trimmed.startsWith("---")) {
            int start = content.indexOf("---");
            int end = content.indexOf("\n---", start + 3);
            if (end > 0) {
                fm = content.substring(start + 3, end).trim();
                int bodyStart = content.indexOf('\n', end + 1);
                body = bodyStart >= 0 ? content.substring(bodyStart + 1) : "";
            }
        }

        if (fm != null && !fm.isBlank()) {
            Object parsed = new Yaml().load(fm);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> meta = (Map<String, Object>) map;
                if (meta.get("name") != null) m.setName(String.valueOf(meta.get("name")));
                if (meta.get("description") != null) m.setDescription(String.valueOf(meta.get("description")));
                if (meta.get("version") != null) m.setVersion(String.valueOf(meta.get("version")));
                if (meta.get("enabled") != null) m.setEnabled(Boolean.parseBoolean(String.valueOf(meta.get("enabled"))));
                Object tools = meta.get("mcpTools");
                if (tools instanceof List<?> tl) {
                    List<String> toolNames = new ArrayList<>();
                    for (Object t : tl) toolNames.add(String.valueOf(t));
                    m.setMcpTools(toolNames);
                }
                Object schedule = meta.get("schedule");
                if (schedule instanceof Map<?, ?> sm) {
                    m.setSchedule(parseSchedule(sm));
                }
                Object source = meta.get("source");
                if (source instanceof Map<?, ?> so) {
                    m.setSource(parseSource(so));
                }
                Object proactive = meta.get("proactive");
                if (proactive instanceof Map<?, ?> pm) {
                    m.setProactive(parseProactive(pm));
                }
            }
        }
        m.setInstructions(body.strip());
        return m;
    }

    String render(SkillManifest m) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", m.getName());
        meta.put("description", m.getDescription() == null ? "" : m.getDescription());
        meta.put("enabled", m.isEnabled());
        meta.put("version", m.getVersion() == null ? "1.0.0" : m.getVersion());
        meta.put("mcpTools", m.getMcpTools() == null ? new ArrayList<>() : m.getMcpTools());
        if (m.getSchedule() != null) {
            meta.put("schedule", scheduleMap(m.getSchedule()));
        }
        if (m.getSource() != null) {
            meta.put("source", sourceMap(m.getSource()));
        }
        if (m.getProactive() != null) {
            meta.put("proactive", proactiveMap(m.getProactive()));
        }

        String yaml = new Yaml(opts).dump(meta);
        return "---\n" + yaml + "---\n\n" + (m.getInstructions() == null ? "" : m.getInstructions().strip()) + "\n";
    }

    private SkillManifest.ScheduleConfig parseSchedule(Map<?, ?> map) {
        SkillManifest.ScheduleConfig cfg = new SkillManifest.ScheduleConfig();
        if (map.get("enabled") != null) cfg.setEnabled(Boolean.parseBoolean(String.valueOf(map.get("enabled"))));
        if (map.get("cron") != null) cfg.setCron(String.valueOf(map.get("cron")));
        if (map.get("zone") != null) cfg.setZone(String.valueOf(map.get("zone")));
        if (map.get("hour") != null) cfg.setHour(parseInt(map.get("hour"), cfg.getHour()));
        if (map.get("minute") != null) cfg.setMinute(parseInt(map.get("minute"), cfg.getMinute()));
        return cfg;
    }

    private SkillManifest.SourceConfig parseSource(Map<?, ?> map) {
        SkillManifest.SourceConfig cfg = new SkillManifest.SourceConfig();
        if (map.get("type") != null) cfg.setType(String.valueOf(map.get("type")));
        if (map.get("url") != null) cfg.setUrl(String.valueOf(map.get("url")));
        if (map.get("fallbackMarkdownRepo") != null) cfg.setFallbackMarkdownRepo(String.valueOf(map.get("fallbackMarkdownRepo")));
        return cfg;
    }

    private SkillManifest.ProactiveConfig parseProactive(Map<?, ?> map) {
        SkillManifest.ProactiveConfig cfg = new SkillManifest.ProactiveConfig();
        if (map.get("enabled") != null) cfg.setEnabled(Boolean.parseBoolean(String.valueOf(map.get("enabled"))));
        if (map.get("topicMode") != null) cfg.setTopicMode(String.valueOf(map.get("topicMode")));
        if (map.get("maxItems") != null) cfg.setMaxItems(parseInt(map.get("maxItems"), cfg.getMaxItems()));
        if (map.get("promptTemplate") != null) cfg.setPromptTemplate(String.valueOf(map.get("promptTemplate")));
        return cfg;
    }

    private int parseInt(Object raw, Integer fallback) {
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception e) {
            return fallback == null ? 0 : fallback;
        }
    }

    private Map<String, Object> scheduleMap(SkillManifest.ScheduleConfig cfg) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", cfg.isEnabled());
        map.put("cron", cfg.getCron() == null ? "" : cfg.getCron());
        map.put("zone", cfg.getZone() == null ? "Asia/Shanghai" : cfg.getZone());
        map.put("hour", cfg.getHour() == null ? 10 : cfg.getHour());
        map.put("minute", cfg.getMinute() == null ? 0 : cfg.getMinute());
        return map;
    }

    private Map<String, Object> sourceMap(SkillManifest.SourceConfig cfg) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", cfg.getType() == null ? "rss" : cfg.getType());
        map.put("url", cfg.getUrl() == null ? "" : cfg.getUrl());
        map.put("fallbackMarkdownRepo", cfg.getFallbackMarkdownRepo() == null ? "" : cfg.getFallbackMarkdownRepo());
        return map;
    }

    private Map<String, Object> proactiveMap(SkillManifest.ProactiveConfig cfg) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", cfg.isEnabled());
        map.put("topicMode", cfg.getTopicMode() == null ? "" : cfg.getTopicMode());
        map.put("maxItems", cfg.getMaxItems());
        map.put("promptTemplate", cfg.getPromptTemplate() == null ? "" : cfg.getPromptTemplate());
        return map;
    }

    private String slug(String name) {
        String s = name.trim().toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return s.isBlank() ? "skill" : s;
    }

    private void seedSample() {
        SkillManifest sample = new SkillManifest();
        sample.setName("web-research");
        sample.setDescription("当用户问到时事、最新资讯、需要查证的事实时，进行联网检索并归纳，附上来源链接");
        sample.setEnabled(true);
        sample.setVersion("1.0.0");
        sample.setMcpTools(new ArrayList<>(List.of("webSearch")));
        sample.setInstructions("""
                ### 何时使用

                用户问到时事、最新资讯、需要查证的事实，且不属于 weather-lookup 天气类问题时，启用本技能。

                ### 使用步骤

                1. 判断问题是否需要最新/外部信息（天气类问题请用 weather-lookup 技能，不要用本技能）。
                2. 若是新闻/时事/查证类，调用 `webSearch` 工具，query 用精炼的关键词。
                3. 阅读返回的标题、摘要与链接，提炼与问题相关的要点。
                4. 用中文简洁作答，并在结尾以"来源："列出引用的链接。
                5. 若搜索结果与问题无关或为空，则坦诚说明并用已有知识回答，不要编造来源。
                """);
        save(sample);
    }

    private void seedWeatherIfMissing() {
        Path weatherDir = appPaths.skillsDir().resolve("weather-lookup");
        if (Files.isRegularFile(weatherDir.resolve("SKILL.md"))) {
            return;
        }
        log.info("写入内置技能 weather-lookup: {}", weatherDir);
        SkillManifest weather = new SkillManifest();
        weather.setDirName("weather-lookup");
        weather.setName("weather-lookup");
        weather.setDescription("查询城市实时天气（气温、现象、湿度），适用于「XX天气怎么样」「今天几度」等");
        weather.setEnabled(true);
        weather.setVersion("1.0.0");
        weather.setMcpTools(new ArrayList<>(List.of("webSearch")));
        weather.setInstructions("""
                ### 何时使用

                用户询问某地今天/现在的天气、气温、冷不冷、下雨吗等与实时气象相关的问题时，启用本技能。

                ### 使用步骤

                1. **确定城市**：从当前用户句或最近几轮对话中提取城市名；若无法确定，用角色口吻请用户说明城市，不要编造气温。
                2. **读取结果**：优先使用系统已注入的「【天气查询结果】」中的【天气实况】数据（含气温、现象、湿度）。
                3. **补充检索**：若注入块为空或缺少气温，调用 `webSearch`，query 必须为「{城市} 天气 今天」（不要用整句口语）。
                4. **作答要求**：用简洁中文说明现象、气温（°C）、湿度/风力（若有）；数据必须来自检索结果，禁止编造。
                5. **人设**：在角色扮演场景下仍遵守上述数据约束，不得因文风模糊而省略具体温度。

                ### 示例 query

                - 北京 → `北京 天气 今天`
                - 上海明天冷吗 → `上海 天气 明天`
                """);
        save(weather);
    }

    private void seedPrimeCheckIfMissing() {
        Path primeDir = appPaths.skillsDir().resolve("prime-check");
        if (Files.isRegularFile(primeDir.resolve("SKILL.md"))) {
            return;
        }
        log.info("写入内置技能 prime-check: {}", primeDir);
        SkillManifest prime = new SkillManifest();
        prime.setDirName("prime-check");
        prime.setName("prime-check");
        prime.setDescription("判断正整数是否为质数，适用于「XX是质数吗」「帮我验一下是不是素数」等");
        prime.setEnabled(true);
        prime.setVersion("1.0.0");
        prime.setMcpTools(new ArrayList<>(List.of("isPrime")));
        prime.setInstructions("""
                ### 何时使用

                用户询问某个**正整数是否为质数/素数**，或需要精确验算时，启用本技能。偶数/奇数、合数分解等不属于本技能。

                ### 使用步骤

                1. 从用户句中提取要判断的正整数；若无法确定，用角色口吻请用户给出数字。
                2. 调用 `isPrime` 工具，参数 `number` 为该整数（不要用口语整句作参数）。
                3. 根据工具返回的「是质数 / 不是质数」结论作答；禁止自行心算或猜测。
                4. 在角色扮演场景下仍须如实传达工具结论，不得用比喻替代判断结果。
                """);
        save(prime);
    }

    private void seedAiDailyIfMissing() {
        Path dailyDir = appPaths.skillsDir().resolve("ai-daily-juya");
        if (Files.isRegularFile(dailyDir.resolve("SKILL.md"))) {
            return;
        }
        log.info("写入内置技能 ai-daily-juya: {}", dailyDir);
        SkillManifest daily = new SkillManifest();
        daily.setDirName("ai-daily-juya");
        daily.setName("ai-daily-juya");
        daily.setDescription("每天 10:00 读取 juya AI 日报；用户询问今天 AI/科技新闻时，也读取当天日报作为回答依据");
        daily.setEnabled(false);
        daily.setVersion("1.0.0");
        daily.setMcpTools(new ArrayList<>());

        SkillManifest.ScheduleConfig schedule = new SkillManifest.ScheduleConfig();
        schedule.setEnabled(true);
        schedule.setCron("0 0 10 * * *");
        schedule.setZone("Asia/Shanghai");
        schedule.setHour(10);
        schedule.setMinute(0);
        daily.setSchedule(schedule);

        SkillManifest.SourceConfig source = new SkillManifest.SourceConfig();
        source.setType("rss");
        source.setUrl("https://daily.juya.uk/rss.xml");
        source.setFallbackMarkdownRepo("");
        daily.setSource(source);

        SkillManifest.ProactiveConfig proactive = new SkillManifest.ProactiveConfig();
        proactive.setEnabled(true);
        proactive.setTopicMode("daily_digest");
        proactive.setMaxItems(20);
        proactive.setPromptTemplate("""
                [System: 你刚读完今天的 AI 日报。请挑 1-2 个和用户可能相关的看点，自然主动开个话题。不要逐条播报全文。必须基于已读取的日报内容，不要编造新闻。]
                """.strip());
        daily.setProactive(proactive);

        daily.setInstructions("""
                ### 何时使用

                1. 每天 10:00 自动读取 juya AI 日报，并可作为主动对话话题。
                2. 用户询问「今天有什么新闻」「今天 AI 有什么新闻」「AI 日报」「今日科技/人工智能资讯」等当天新闻问题时，读取当天 AI 日报再回答。

                ### 使用步骤

                1. 优先使用系统已注入的「【AI 日报 · 技能 ai-daily-juya】」内容。
                2. 用户追问某条新闻的具体内容时，必须优先使用系统注入的「日报原始链接检索」结果；没有检索结果时要明确说明。
                3. 回答时只概括关键看点，不要照搬全文。
                4. 事实必须来自日报条目或原始链接检索结果；若未读取到日报，说明暂时没有读到，不要编造。
                5. 可以附上日报条目的来源链接。
                """);
        save(daily);
    }
}
