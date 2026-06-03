package org.example.aichat.service.memos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.config.MemosProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemosClient {

    private static final int MAX_ITEM_CHARS = 400;
    private static final List<String> LIST_FIELDS = List.of(
            // Memos /product/search 返回的分组字段
            "text_mem", "act_mem", "pref_mem", "tool_mem", "skill_mem",
            // 通用列表字段（兼容旧格式）
            "memories", "memory_list", "items", "results", "memory_nodes", "nodes", "records", "data");
    private static final List<String> TEXT_FIELDS = List.of(
            "text", "content", "memory", "summary", "value");

    /**
     * Memos 记忆类型枚举（对齐 metadata.memory_type 字段）
     * - USER: 关于用户的事实记忆（喜好、经历、属性）
     * - LONG_TERM: 长期记忆/日记（角色 RP 视角的回忆）
     * - WORKING: 工作记忆（短期）
     * - PREFERENCE: 偏好记忆（pref_mem 分组返回）
     * - UNKNOWN: 无法识别的类型
     */
    public enum MemoryType {
        USER, LONG_TERM, WORKING, PREFERENCE, UNKNOWN;

        public static MemoryType from(String raw) {
            if (raw == null) return UNKNOWN;
            String s = raw.trim();
            if (s.equalsIgnoreCase("UserMemory")) return USER;
            if (s.equalsIgnoreCase("LongTermMemory")) return LONG_TERM;
            if (s.equalsIgnoreCase("WorkingMemory")) return WORKING;
            if (s.equalsIgnoreCase("PreferenceMemory") || s.equalsIgnoreCase("Preference")) return PREFERENCE;
            return UNKNOWN;
        }
    }

    /**
     * 结构化记忆项 —— 给上层做按类型分段注入用
     */
    public record MemoryItem(String id, String text, MemoryType type, double relativity) {}

    /**
     * Memos 搜索结果 —— 按类型分组
     */
    public record SearchResult(List<MemoryItem> userMemories,
                               List<MemoryItem> longTermMemories,
                               List<MemoryItem> preferenceMemories,
                               List<MemoryItem> others) {
        public static SearchResult empty() {
            return new SearchResult(List.of(), List.of(), List.of(), List.of());
        }
        public boolean isEmpty() {
            return userMemories.isEmpty() && longTermMemories.isEmpty()
                    && preferenceMemories.isEmpty() && others.isEmpty();
        }
        public int total() {
            return userMemories.size() + longTermMemories.size()
                    + preferenceMemories.size() + others.size();
        }
    }

    private final MemosProperties props;
    private final ObjectMapper objectMapper;

    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        if (!props.isEnabled()) {
            log.info("MemOS client disabled");
            return;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        restTemplate = new RestTemplate(factory);
        log.info("MemOS client ready: baseUrl={}, asyncMode={}, writableCubeIds={}, readableCubeIds={}",
                props.getBaseUrl(), props.getAsyncMode(),
                props.getWritableCubeIds(), props.getReadableCubeIds());
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    public boolean isFallbackToRag() {
        return props.isFallbackToRag();
    }

    public int defaultSearchTopK() {
        return props.getSearchTopK();
    }

    // ======================== Add Memory ========================

    /**
     * 添加长期记忆 —— 使用结构化 messages 格式（对齐 Memos /product/add API）。
     * messages 会被构建为 [{"role": "user", "content": "..."}] 格式。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID（可选）
     * @param roleId    角色 ID（可选，写入 info 元数据）
     * @param memoryText 记忆文本内容
     * @return 是否添加成功
     */
    public boolean addLongTermMemory(String userId, String sessionId, Integer roleId, String memoryText) {
        if (!isEnabled() || !StringUtils.hasText(userId) || !StringUtils.hasText(memoryText)) {
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        if (StringUtils.hasText(sessionId)) {
            body.put("session_id", sessionId);
        }
        body.put("async_mode", props.getAsyncMode());

        // 结构化 messages：对齐 API 规范，传 [{"role":"user","content":"..."}]
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", memoryText);
        messages.add(msg);
        body.put("messages", messages);

        // writable_cube_ids —— 多 cube 写入
        List<String> writableCubeIds = props.parseWritableCubeIds();
        if (!writableCubeIds.isEmpty()) {
            body.put("writable_cube_ids", writableCubeIds);
        }

        // custom_tags
        body.put("custom_tags", List.of("long_term_memory"));

        // info 元数据：所有 key 可作为 search filter
        Map<String, Object> info = new LinkedHashMap<>();
        if (roleId != null) {
            info.put("role_id", roleId);
        }
        if (StringUtils.hasText(sessionId)) {
            info.put("conversation_id", sessionId);
        }
        info.put("source", "ai-chat");
        info.put("memory_type", "long_term_diary");
        body.put("info", info);

        String resp = post("/product/add", body);
        if (resp != null) {
            log.debug("MemOS add memory success: userId={}, roleId={}", userId, roleId);
        }
        return resp != null;
    }

    /**
     * 添加用户消息记忆 —— 严格按照 Memos 官方推荐用法：
     * 只推送一条 role=user 的消息，让 Memos 内部 MemReader 把它加工为 UserMemory（用户事实）。
     *
     * 设计原因：
     * - Memos 的 MemReader 会按 messages 数组中"对话发生过的语境"来抽取事实
     * - 如果同时推送 user 和 assistant，Memos LLM 可能把 assistant 的幻觉/陈述
     *   误归到"用户事实"上（已观察到污染现象）
     * - 官方文档示例：messages = [{"role": "user", "content": "我喜欢草莓"}]
     *
     * @param userId    Memos user_id（UUID）
     * @param sessionId 会话 ID（可选，用于 soft-filtering）
     * @param roleId    业务侧角色 ID（可选，写入 info.role_id）
     * @param userMsg   用户原话
     * @return 是否添加成功
     */
    public boolean addUserMessage(String userId, String sessionId, Integer roleId, String userMsg) {
        if (!isEnabled() || !StringUtils.hasText(userId) || !StringUtils.hasText(userMsg)) {
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        if (StringUtils.hasText(sessionId)) {
            body.put("session_id", sessionId);
        }
        body.put("async_mode", props.getAsyncMode());

        // 仅推一条 user 消息 —— 杜绝 assistant 内容污染 UserMemory 桶
        Map<String, String> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", userMsg);
        body.put("messages", List.of(userMessage));

        List<String> writableCubeIds = props.parseWritableCubeIds();
        if (!writableCubeIds.isEmpty()) {
            body.put("writable_cube_ids", writableCubeIds);
        }

        Map<String, Object> info = new LinkedHashMap<>();
        if (roleId != null) {
            info.put("role_id", roleId);
        }
        if (StringUtils.hasText(sessionId)) {
            info.put("conversation_id", sessionId);
        }
        info.put("source", "ai-chat");
        info.put("memory_type", "user_fact");
        body.put("info", info);

        String resp = post("/product/add", body);
        if (resp != null) {
            log.debug("MemOS add user message success: userId={}, roleId={}, len={}",
                    userId, roleId, userMsg.length());
        }
        return resp != null;
    }

    // ======================== Search Memory ========================

    /**
     * 搜索记忆 —— 对齐 Memos /product/search API。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID（可选，用于 soft-filtering）
     * @param roleId    角色 ID（可选，用于 filter）
     * @param query     搜索查询
     * @param topK      返回条数（null 则用默认值）
     * @return 匹配的记忆文本列表
     */
    public List<String> searchMemories(String userId, String sessionId, Integer roleId, String query, Integer topK) {
        SearchResult result = searchStructured(userId, sessionId, roleId, query, topK);
        if (result.isEmpty()) return List.of();
        // 旧接口兼容：合并所有记忆为单一文本列表（已不推荐使用，请走 searchStructured）
        List<String> texts = new ArrayList<>(result.total());
        result.userMemories().forEach(m -> texts.add(m.text()));
        result.longTermMemories().forEach(m -> texts.add(m.text()));
        result.others().forEach(m -> texts.add(m.text()));
        result.preferenceMemories().forEach(m -> texts.add(m.text()));
        int limit = (topK != null && topK > 0) ? topK : props.getSearchTopK();
        return texts.size() > limit ? texts.subList(0, limit) : texts;
    }

    /**
     * 结构化搜索 —— 按 memory_type 分组返回，用于上层做差异化 prompt 注入。
     * UserMemory（用户事实）、LongTermMemory（角色日记）会被分到不同字段，
     * pref_mem（偏好记忆）单独成一组。
     */
    public SearchResult searchStructured(String userId, String sessionId, Integer roleId, String query, Integer topK) {
        if (!isEnabled() || !StringUtils.hasText(userId) || !StringUtils.hasText(query)) {
            return SearchResult.empty();
        }
        int limit = (topK != null && topK > 0) ? topK : props.getSearchTopK();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        body.put("query", query);
        body.put("top_k", limit);
        body.put("mode", props.getSearchMode());
        body.put("relativity", props.getRelativity());
        body.put("include_preference", props.isIncludePreference());
        body.put("pref_top_k", props.getPrefTopK());

        if (StringUtils.hasText(sessionId)) {
            body.put("session_id", sessionId);
        }

        List<String> readableCubeIds = props.parseReadableCubeIds();
        if (!readableCubeIds.isEmpty()) {
            body.put("readable_cube_ids", readableCubeIds);
        }

        if (props.isRoleFilterEnabled() && roleId != null) {
            Map<String, Object> filter = new LinkedHashMap<>();
            List<Map<String, Object>> clauses = new ArrayList<>();
            clauses.add(Map.of("role_id", roleId));
            clauses.add(Map.of("info.role_id", roleId));
            filter.put("and", clauses);
            body.put("filter", filter);
        }

        String resp = post("/product/search", body);
        if (resp == null && body.containsKey("filter")) {
            body.remove("filter");
            resp = post("/product/search", body);
        }
        if (resp == null) {
            log.warn("MemOS search returned null for userId={}, query={}", userId, query);
            return SearchResult.empty();
        }
        SearchResult parsed = parseStructuredResponse(resp, limit);
        log.info("MemOS search: query='{}', mode={}, topK={}, user={}, longTerm={}, pref={}, others={}",
                query.length() > 30 ? query.substring(0, 30) + "..." : query,
                props.getSearchMode(), limit,
                parsed.userMemories().size(), parsed.longTermMemories().size(),
                parsed.preferenceMemories().size(), parsed.others().size());
        return parsed;
    }

    // ======================== Feedback ========================

    /**
     * 记忆反馈/纠错 —— 对齐 Memos /product/feedback API。
     * 用户指出 AI 回复有误时，用此接口修正相关记忆。
     *
     * @param userId           用户 ID
     * @param sessionId        会话 ID
     * @param feedbackContent  反馈内容（自然语言，如"会议地点不是北京是上海"）
     * @param history          对话历史（可选，用于自动定位冲突记忆）
     * @param retrievedMemoryIds 上一轮检索到的记忆 IDs（可选，用于精准定位）
     * @return 是否反馈成功
     */
    public boolean feedback(String userId, String sessionId, String feedbackContent,
                            List<Map<String, String>> history, List<String> retrievedMemoryIds) {
        if (!isEnabled() || !StringUtils.hasText(userId) || !StringUtils.hasText(feedbackContent)) {
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        body.put("feedback_content", feedbackContent);
        body.put("async_mode", props.getAsyncMode());

        if (StringUtils.hasText(sessionId)) {
            body.put("session_id", sessionId);
        }
        if (history != null && !history.isEmpty()) {
            body.put("history", history);
        }
        if (retrievedMemoryIds != null && !retrievedMemoryIds.isEmpty()) {
            body.put("retrieved_memory_ids", retrievedMemoryIds);
        }

        // writable_cube_ids
        List<String> writableCubeIds = props.parseWritableCubeIds();
        if (!writableCubeIds.isEmpty()) {
            body.put("writable_cube_ids", writableCubeIds);
        }

        String resp = post("/product/feedback", body);
        if (resp != null) {
            log.info("MemOS feedback success: userId={}", userId);
        }
        return resp != null;
    }

    // ======================== Delete Memory ========================

    /**
     * 删除记忆 —— 按 memory_ids 或 user_id + session_id 批量删除。
     *
     * @param memoryIds  要删除的记忆 ID 列表（可选）
     * @param userId     用户 ID（可选，快速删除该用户所有记忆）
     * @param sessionId  会话 ID（可选，快速删除该会话记忆）
     * @return 是否删除成功
     */
    public boolean deleteMemories(List<String> memoryIds, String userId, String sessionId) {
        if (!isEnabled()) return false;

        Map<String, Object> body = new LinkedHashMap<>();
        if (memoryIds != null && !memoryIds.isEmpty()) {
            body.put("memory_ids", memoryIds);
        }
        if (StringUtils.hasText(userId)) {
            body.put("user_id", userId);
        }
        if (StringUtils.hasText(sessionId)) {
            body.put("session_id", sessionId);
        }

        List<String> writableCubeIds = props.parseWritableCubeIds();
        if (!writableCubeIds.isEmpty()) {
            body.put("writable_cube_ids", writableCubeIds);
        }

        if (body.isEmpty()) return false;

        String resp = post("/product/delete_memory", body);
        if (resp != null) {
            log.info("MemOS delete memories success");
        }
        return resp != null;
    }

    // ======================== Health Check ========================

    /**
     * 检查 Memos 服务是否可用
     */
    public boolean isHealthy() {
        if (!isEnabled() || restTemplate == null) return false;
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    endpoint("/health"), String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("MemOS health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ======================== Internal ========================

    private String post(String path, Map<String, Object> body) {
        if (restTemplate == null) {
            init();
        }
        if (restTemplate == null) {
            return null;
        }
        try {
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, buildHeaders());
            ResponseEntity<String> resp = restTemplate.postForEntity(endpoint(path), req, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("MemOS call failed: path={}, status={}", path, resp.getStatusCode());
                return null;
            }
            return resp.getBody();
        } catch (RestClientException e) {
            log.warn("MemOS call error: path={}, message={}", path, e.getMessage());
            return null;
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (StringUtils.hasText(props.getApiKey())) {
            headers.setBearerAuth(props.getApiKey());
        }
        return headers;
    }

    private String endpoint(String path) {
        String base = props.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    /**
     * 结构化解析 Memos /product/search 响应。
     * 响应结构（关键路径）：
     *   data.text_mem[].memories[].metadata.memory_type   —— "UserMemory" / "LongTermMemory" / "WorkingMemory"
     *   data.text_mem[].memories[].memory                 —— 文本内容
     *   data.text_mem[].memories[].id                     —— memory id
     *   data.text_mem[].memories[].metadata.relativity    —— 相关性
     *   data.pref_mem[].memories[]                        —— 偏好记忆（独立分组）
     */
    private SearchResult parseStructuredResponse(String body, int topK) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return SearchResult.empty();
            }

            List<MemoryItem> userMems = new ArrayList<>();
            List<MemoryItem> longTermMems = new ArrayList<>();
            List<MemoryItem> prefMems = new ArrayList<>();
            List<MemoryItem> others = new ArrayList<>();

            // text_mem 分组：UserMemory / LongTermMemory / WorkingMemory 都在这里
            collectFromGroup(data.path("text_mem"), (item) -> {
                switch (item.type()) {
                    case USER -> userMems.add(item);
                    case LONG_TERM -> longTermMems.add(item);
                    case WORKING -> others.add(item);
                    default -> others.add(item);
                }
            });

            // pref_mem 分组：明确为偏好记忆
            collectFromGroup(data.path("pref_mem"), (item) -> {
                MemoryItem normalized = item.type() == MemoryType.UNKNOWN
                        ? new MemoryItem(item.id(), item.text(), MemoryType.PREFERENCE, item.relativity())
                        : item;
                prefMems.add(normalized);
            });

            // 截断到 topK（按比例分配，避免某一类全占满）
            return new SearchResult(
                    capList(sortByRelativity(userMems), topK),
                    capList(sortByRelativity(longTermMems), topK),
                    capList(sortByRelativity(prefMems), props.getPrefTopK()),
                    capList(sortByRelativity(others), topK)
            );
        } catch (Exception e) {
            log.warn("MemOS search parse failed: {}", e.getMessage());
            return SearchResult.empty();
        }
    }

    /**
     * 从 text_mem / pref_mem 这种分组节点中遍历 cube → memories → 单条记忆。
     * 节点结构：[ { cube_id, memories:[{id, memory, metadata:{memory_type, relativity}}] } ]
     */
    private void collectFromGroup(JsonNode groupNode, java.util.function.Consumer<MemoryItem> consumer) {
        if (groupNode == null || !groupNode.isArray()) return;
        for (JsonNode cube : groupNode) {
            JsonNode memories = cube.path("memories");
            if (!memories.isArray()) continue;
            for (JsonNode mem : memories) {
                MemoryItem item = parseMemoryItem(mem);
                if (item != null) consumer.accept(item);
            }
        }
    }

    private MemoryItem parseMemoryItem(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        String text = pickTextField(node);
        if (!StringUtils.hasText(text)) return null;
        text = normalize(text);

        String id = node.path("id").asText("");
        JsonNode metadata = node.path("metadata");
        String typeRaw = metadata.path("memory_type").asText(null);
        double relativity = metadata.path("relativity").asDouble(0.0);

        return new MemoryItem(id, text, MemoryType.from(typeRaw), relativity);
    }

    private List<MemoryItem> sortByRelativity(List<MemoryItem> list) {
        list.sort((a, b) -> Double.compare(b.relativity(), a.relativity()));
        return list;
    }

    private List<MemoryItem> capList(List<MemoryItem> list, int max) {
        if (max <= 0 || list.size() <= max) return list;
        return new ArrayList<>(list.subList(0, max));
    }

    private String pickTextField(JsonNode node) {
        for (String field : TEXT_FIELDS) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        }
        return null;
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        String normalized = raw.replaceAll("\\s+", " ").trim();
        if (normalized.length() > MAX_ITEM_CHARS) {
            normalized = normalized.substring(0, MAX_ITEM_CHARS) + "...";
        }
        return normalized;
    }
}