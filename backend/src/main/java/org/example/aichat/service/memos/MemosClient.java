package org.example.aichat.service.memos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.config.MemosProperties;
import org.example.aichat.dto.RoleCard;
import org.example.aichat.mapper.RoleCardMapper;
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

    /** Memos 官方 session 软信号 + 本侧硬过滤用的角色桶前缀（写入时打在 session_id 上） */
    public static final String ROLE_SESSION_PREFIX = "role_";
    private static final String LEGACY_SESSION_ID = "default_session";
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
    private final RoleCardMapper roleCardMapper;
    private final ObjectMapper objectMapper;

    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        refreshClient();
    }

    /** 运行时配置变更后重建 MemOS HTTP 客户端 */
    public void refreshClient() {
        if (!props.isEnabled()) {
            restTemplate = null;
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
     * @param roleId    角色 ID（可选，映射为 session_id=role_{id} 与角色 cube）
     * @param memoryText 记忆文本内容
     * @return 是否添加成功
     */
    public boolean addLongTermMemory(String userId, String sessionId, Integer roleId, String memoryText) {
        if (!isEnabled() || !StringUtils.hasText(userId) || !StringUtils.hasText(memoryText)) {
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        String effectiveSessionId = resolveSessionId(roleId, sessionId);
        if (StringUtils.hasText(effectiveSessionId)) {
            body.put("session_id", effectiveSessionId);
        }
        body.put("async_mode", "sync");

        // 结构化 messages：对齐 API 规范，传 [{"role":"user","content":"..."}]
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", memoryText);
        messages.add(msg);
        body.put("messages", messages);

        List<String> writableCubeIds = resolveWritableCubeIds(roleId);
        if (!writableCubeIds.isEmpty()) {
            body.put("writable_cube_ids", writableCubeIds);
        }

        body.put("custom_tags", List.of("long_term_memory", roleTag(roleId)));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("source", "ai-chat");
        info.put("memory_type", "long_term_diary");
        if (roleId != null) {
            info.put("role_id", roleId);
        }
        if (StringUtils.hasText(sessionId)) {
            info.put("conversation_id", sessionId);
        }
        body.put("info", info);

        return submitAdd(body, userId, roleId, effectiveSessionId, "long_term");
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
     * @param sessionId 会话 ID（可选；有 roleId 时以 role_{id} 为准）
     * @param roleId    业务侧 role_card.id
     * @param userMsg   用户原话
     * @return 是否添加成功
     */
    public boolean addUserMessage(String userId, String sessionId, Integer roleId, String userMsg) {
        if (!isEnabled() || !StringUtils.hasText(userId) || !StringUtils.hasText(userMsg)) {
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        String effectiveSessionId = resolveSessionId(roleId, sessionId);
        if (StringUtils.hasText(effectiveSessionId)) {
            body.put("session_id", effectiveSessionId);
        }
        body.put("async_mode", "sync");

        // 仅推一条 user 消息 —— 杜绝 assistant 内容污染 UserMemory 桶
        Map<String, String> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", userMsg);
        body.put("messages", List.of(userMessage));

        List<String> writableCubeIds = resolveWritableCubeIds(roleId);
        if (!writableCubeIds.isEmpty()) {
            body.put("writable_cube_ids", writableCubeIds);
        }

        body.put("custom_tags", List.of(roleTag(roleId)));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("source", "ai-chat");
        info.put("memory_type", "user_fact");
        if (roleId != null) {
            info.put("role_id", roleId);
        }
        if (StringUtils.hasText(sessionId)) {
            info.put("conversation_id", sessionId);
        }
        body.put("info", info);

        return submitAdd(body, userId, roleId, effectiveSessionId, "user_message");
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
        String roleSessionId = roleId != null ? roleSessionId(roleId) : null;
        int fetchLimit = roleSessionId != null ? Math.max(limit * 3, 30) : limit;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        body.put("query", query);
        body.put("top_k", fetchLimit);
        body.put("mode", props.getSearchMode());
        body.put("relativity", props.getRelativity());
        body.put("include_preference", props.isIncludePreference());
        body.put("pref_top_k", props.getPrefTopK());

        // 官方文档：session_id 仅作相关性加权，非硬过滤；硬过滤在 parse 阶段按 metadata.session_id 完成
        String searchSessionId = roleSessionId != null ? roleSessionId : sessionId;
        if (StringUtils.hasText(searchSessionId)) {
            body.put("session_id", searchSessionId);
        }

        List<String> readableCubeIds = resolveReadableCubeIds(roleId);
        if (!readableCubeIds.isEmpty()) {
            body.put("readable_cube_ids", readableCubeIds);
        }

        String resp = post("/product/search", body);
        if (resp == null) {
            log.warn("MemOS search returned null for userId={}, query={}", userId, query);
            return SearchResult.empty();
        }
        SearchResult parsed = parseStructuredResponse(resp, limit, roleId);
        log.info("MemOS search: query='{}', mode={}, topK={}, roleSession={}, cubes={}, user={}, longTerm={}, pref={}, others={}",
                query.length() > 30 ? query.substring(0, 30) + "..." : query,
                props.getSearchMode(), limit, roleSessionId, readableCubeIds,
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

    // ======================== Role isolation ========================

    /**
     * 角色记忆桶 session_id。Memos 会持久化该字段；搜索 API 仅软加权，本侧再硬过滤。
     */
    public static String roleSessionId(Integer roleId) {
        return roleId == null ? null : ROLE_SESSION_PREFIX + roleId;
    }

    private static String roleTag(Integer roleId) {
        return roleId == null ? "role_unknown" : ROLE_SESSION_PREFIX + roleId;
    }

    private String resolveSessionId(Integer roleId, String conversationSessionId) {
        if (roleId != null) {
            return roleSessionId(roleId);
        }
        return conversationSessionId;
    }

    private List<String> resolveWritableCubeIds(Integer roleId) {
        String roleCube = lookupRoleCubeId(roleId);
        if (StringUtils.hasText(roleCube)) {
            return List.of(roleCube.trim());
        }
        return props.parseWritableCubeIds();
    }

    private List<String> resolveReadableCubeIds(Integer roleId) {
        String roleCube = lookupRoleCubeId(roleId);
        if (StringUtils.hasText(roleCube)) {
            return List.of(roleCube.trim());
        }
        return props.parseReadableCubeIds();
    }

    private String lookupRoleCubeId(Integer roleId) {
        if (roleId == null) {
            return null;
        }
        try {
            RoleCard role = roleCardMapper.findById(roleId);
            if (role != null && StringUtils.hasText(role.getMemosCubeId())) {
                return role.getMemosCubeId().trim();
            }
        } catch (Exception e) {
            log.warn("读取角色 memos_cube_id 失败 roleId={}: {}", roleId, e.getMessage());
        }
        return null;
    }

    /**
     * 提交 /product/add 并校验 MemReader 是否产出记忆。
     * 写入固定 sync：async 仅返回 200 时 data 可能为空，MemReader 失败也无从感知。
     */
    private boolean submitAdd(Map<String, Object> body, String userId, Integer roleId,
                              String sessionId, String kind) {
        String resp = post("/product/add", body);
        if (!StringUtils.hasText(resp)) {
            log.warn("MemOS add {} failed: empty HTTP response, userId={}, roleId={}, sessionId={}",
                    kind, userId, roleId, sessionId);
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(resp);
            int code = root.path("code").asInt(-1);
            if (code != 200) {
                log.warn("MemOS add {} failed: code={}, message={}, roleId={}, sessionId={}",
                        kind, code, root.path("message").asText(""), roleId, sessionId);
                return false;
            }
            JsonNode data = root.path("data");
            int extracted = data.isArray() ? data.size() : 0;
            if (extracted == 0) {
                log.warn("MemOS add {} returned 200 but no extracted memories, roleId={}, sessionId={}, body={}",
                        kind, roleId, sessionId, summarizeUserContent(body));
                return false;
            }
            log.info("MemOS add {} ok: roleId={}, sessionId={}, memories={}", kind, roleId, sessionId, extracted);
            return true;
        } catch (Exception e) {
            log.warn("MemOS add {} parse failed: roleId={}, sessionId={}, err={}", kind, roleId, sessionId, e.getMessage());
            return false;
        }
    }

    private String summarizeUserContent(Map<String, Object> body) {
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        Object first = list.get(0);
        if (first instanceof Map<?, ?> msg) {
            Object content = msg.get("content");
            if (content != null) {
                String text = content.toString();
                return text.length() > 40 ? text.substring(0, 40) + "..." : text;
            }
        }
        return "";
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
    private SearchResult parseStructuredResponse(String body, int topK, Integer roleId) {
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
            collectFromGroup(data.path("text_mem"), roleId, (item) -> {
                switch (item.type()) {
                    case USER -> userMems.add(item);
                    case LONG_TERM -> longTermMems.add(item);
                    case WORKING -> others.add(item);
                    default -> others.add(item);
                }
            });

            // pref_mem 分组：明确为偏好记忆
            collectFromGroup(data.path("pref_mem"), roleId, (item) -> {
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
    private void collectFromGroup(JsonNode groupNode, Integer roleId,
                                  java.util.function.Consumer<MemoryItem> consumer) {
        if (groupNode == null || !groupNode.isArray()) return;
        for (JsonNode cube : groupNode) {
            JsonNode memories = cube.path("memories");
            if (!memories.isArray()) continue;
            for (JsonNode mem : memories) {
                MemoryItem item = parseMemoryItem(mem, roleId);
                if (item != null) consumer.accept(item);
            }
        }
    }

    private MemoryItem parseMemoryItem(JsonNode node, Integer roleId) {
        if (node == null || !node.isObject()) return null;
        if (roleId != null && !matchesRoleSession(node.path("metadata"), roleId)) {
            return null;
        }
        String text = pickTextField(node);
        if (!StringUtils.hasText(text)) return null;
        text = normalize(text);

        String id = node.path("id").asText("");
        JsonNode metadata = node.path("metadata");
        String typeRaw = metadata.path("memory_type").asText(null);
        double relativity = metadata.path("relativity").asDouble(0.0);

        return new MemoryItem(id, text, MemoryType.from(typeRaw), relativity);
    }

    /**
     * 按 metadata.session_id 硬过滤角色记忆。
     * MemReader 不保留 info.role_id，但会保留写入时的 session_id（实测 role_1 / role_6 等）。
     */
    private boolean matchesRoleSession(JsonNode metadata, Integer roleId) {
        if (roleId == null) {
            return true;
        }
        String expected = roleSessionId(roleId);
        String actual = metadata.path("session_id").asText("");
        if (expected.equals(actual)) {
            return true;
        }
        return props.isIncludeLegacyMemories() && LEGACY_SESSION_ID.equals(actual);
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