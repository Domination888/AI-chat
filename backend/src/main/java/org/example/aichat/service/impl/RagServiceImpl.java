package org.example.aichat.service.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.rag.RagChunk;
import org.example.aichat.service.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagServiceImpl implements RagService {

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    private static final String REDIS_RAG_KEY = "rag:chunks:embeds";

    @Value("${rag.chunk-size:500}")
    private int chunkSize;

    @Value("${rag.chunk-overlap:80}")
    private int chunkOverlap;

    @Value("${rag.max-context-chars:2400}")
    private int maxContextChars;

    @Value("${rag.min-similarity-score:0.8}")
    private float minSimilarityScore;

    @Value("${rag.embedding-batch-size:32}")
    private int embeddingBatchSize;

    @Value("${rag.eager-init:false}")
    private boolean eagerInit;

    /**
     * 启动时强制重建：true 则忽略 Redis 旧缓存，每次启动都重新扫描 + 向量化（异步，不阻塞主流程）。
     * Embedding 服务挂掉时会回退到 Redis 旧缓存，保证可用性。
     */
    @Value("${rag.force-rebuild-on-startup:true}")
    private boolean forceRebuildOnStartup;

    private volatile List<RagChunk> chunks = List.of();

    /**
     * 启动期异步预热 future：
     *   - 服务启动不被 embedding 远程调用阻塞
     *   - 首次 retrieveContext 时若仍未完成，则 join 同步等一下（避免"前几次提问 RAG 没生效"的体感问题）
     *   - 预热失败后会被重置为 null，允许后续手动 /api/rag/reload 重试
     */
    private volatile java.util.concurrent.CompletableFuture<Integer> warmupFuture;

    @PostConstruct
    public void init() {
        // 模式 A：force-rebuild-on-startup=true（默认）
        //   每次启动都重建：先把 Redis 旧缓存读到内存里"垫底"（保证 embedding 慢/挂时仍能检索），
        //   然后后台异步重新扫描 + 向量化，新结果完成后无缝替换。
        //   适合 personas/* 经常迭代的开发期 —— 改了语料/persona/memory_cards 重启就生效，不用手动 POST /api/rag/reload。
        // 模式 B：force-rebuild-on-startup=false
        //   保留旧逻辑：Redis 命中即停；未命中且 eager-init=true 才异步预热。
        if (forceRebuildOnStartup) {
            int fallback = loadFromRedis();
            if (fallback > 0) {
                log.info("启动重建模式：先用 Redis 旧缓存垫底（{} 个分块），后台异步重建中...", fallback);
            } else {
                log.info("启动重建模式：Redis 无旧缓存，后台异步从零重建...");
            }
            warmupFuture = java.util.concurrent.CompletableFuture.supplyAsync(this::rebuildSafely);
            return;
        }

        // 模式 B：兼容旧行为
        int count = loadFromRedis();
        if (count > 0) {
            log.info("RAG 初始化完成（命中 Redis 缓存），分块数: {}", count);
            return;
        }
        if (!eagerInit) {
            log.warn("RAG 未命中 Redis 缓存且 rag.eager-init=false，跳过启动期向量化；"
                    + "可在 Win LM Studio 就绪后 POST /api/rag/reload 手动触发");
            return;
        }
        log.info("Redis 中无分块数据，启动后台线程异步向量化（不阻塞主流程）...");
        warmupFuture = java.util.concurrent.CompletableFuture.supplyAsync(this::rebuildSafely);
    }

    /**
     * 后台重建包装：失败时不抛、不清空已有 chunks（保留 Redis 垫底数据，避免"启动后 RAG 短暂全空"）。
     */
    private int rebuildSafely() {
        try {
            int n = reload();
            log.info("RAG 启动重建完成，分块数: {}", n);
            return n;
        } catch (Exception e) {
            log.error("RAG 启动重建失败（保留 Redis 旧缓存，可稍后 POST /api/rag/reload 重试）：{}", e.getMessage());
            return chunks.size();
        }
    }

    private int loadFromRedis() {
        try {
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(REDIS_RAG_KEY);
            if (entries.isEmpty()) {
                return 0;
            }
            List<RagChunk> rebuilt = new ArrayList<>();
            for (Object value : entries.values()) {
                RagChunk chunk = objectMapper.readValue((String) value, RagChunk.class);
                rebuilt.add(chunk);
            }
            chunks = List.copyOf(rebuilt);
            log.info("成功从 Redis 加载 {} 个分块数据", chunks.size());
            return chunks.size();
        } catch (Exception e) {
            log.error("从 Redis 加载 RAG 失败", e);
            return 0;
        }
    }

    private void saveToRedis(List<RagChunk> rebuilt) {
        try {
            stringRedisTemplate.delete(REDIS_RAG_KEY);
            if (rebuilt.isEmpty()) {
                return;
            }
            Map<String, String> map = new HashMap<>();
            for (RagChunk chunk : rebuilt) {
                String key = chunk.source() + "_" + chunk.chunkIndex();
                map.put(key, objectMapper.writeValueAsString(chunk));
            }
            stringRedisTemplate.opsForHash().putAll(REDIS_RAG_KEY, map);
            log.info("成功同步 {} 个分块数据到 Redis (Key: {})", rebuilt.size(), REDIS_RAG_KEY);
        } catch (Exception e) {
            log.error("同步 RAG 数据到 Redis 失败", e);
        }
    }


    @Override
    public String retrieveContext(String query, int topK) {
        return retrieveContext(null, query, topK);
    }

    @Override
    public String retrieveContext(String roleCode, String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return "";
        }
        // 启动期可能还在异步预热：第一次提问时同步等一下，最多 30s（embedding 慢的话也认了，比"RAG 静默失效"好）
        awaitWarmupIfNeeded();
        if (chunks.isEmpty()) {
            log.debug("RAG chunks 为空（预热未完成或失败），本次跳过检索");
            return "";
        }

        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            float[] queryVec = queryEmbedding.vector();

            String normalizedRoleCode = StringUtils.hasText(roleCode)
                    ? roleCode.trim().toLowerCase(Locale.ROOT)
                    : null;

            List<RagChunk> candidateChunks = chunks;
            if (StringUtils.hasText(normalizedRoleCode)) {
                String prefix = normalizedRoleCode + "_";
                candidateChunks = chunks.stream()
                        .filter(c -> c.source() != null && c.source().toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }

            if (candidateChunks.isEmpty()) {
                return "";
            }

            // 计算原始相似度并对 memory_cards 类型的分块加权（PLAN-005 阶段5：memory_cards 系数 ×1.3）
            List<ScoredChunk> ranked = candidateChunks.stream()
                    .map(chunk -> {
                        float raw = cosineSimilarity(queryVec, chunk.embedding());
                        float weighted = isMemoryCard(chunk) ? raw * 1.3f : raw;
                        return new ScoredChunk(chunk, weighted);
                    })
                    .filter(sc -> sc.score > minSimilarityScore)
                    .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                    .limit(topK)
                    .toList();

            if (ranked.isEmpty()) {
                log.debug("向量检索无命中(query={}, roleCode={}, threshold={})", query, roleCode, minSimilarityScore);
                return "";
            }

            StringBuilder context = new StringBuilder();
            context.append("【本地知识库检索结果】\n");

            int used = 0;
            int idx = 1;
            for (ScoredChunk scored : ranked) {
                String item = String.format("[片段%d|来源:%s#%d|相似度:%.2f]\n%s\n\n",
                        idx, scored.chunk.source(), scored.chunk.chunkIndex(), scored.score, scored.chunk.text().trim());
                if (used + item.length() > maxContextChars) {
                    break;
                }
                context.append(item);
                used += item.length();
                idx++;
            }

            if (context.length() <= "【本地知识库检索结果】\n".length()) {
                return "";
            }
            return context.toString();
        } catch (Exception e) {
            log.error("向量检索失败 query={}, roleCode={}", query, roleCode, e);
            return "";
        }
    }

    /**
     * 启动期 RAG 还在异步预热时，首次检索同步等一下（最多 30s），避免"前几次提问 RAG 没命中"的诡异体感。
     * 完成后清掉 future 引用，后续调用零开销。
     */
    private void awaitWarmupIfNeeded() {
        java.util.concurrent.CompletableFuture<Integer> f = warmupFuture;
        if (f == null || f.isDone()) {
            return;
        }
        try {
            log.info("检索请求到达但 RAG 仍在预热中，同步等待预热完成（最多 30s）...");
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("等待 RAG 预热超时/失败，本次按空索引处理：{}", e.getMessage());
        } finally {
            warmupFuture = null;
        }
    }

    private float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0f;
        }
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denom > 1e-6f ? dot / denom : 0f;
    }

    @Override
    public int reload() {
        List<RagChunk> rebuilt = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            // 全局知识库（兼容历史 rag/ 目录，仅顶层 txt/md，作为可选项保留）
            org.springframework.core.io.Resource[] globalTxt = resolver.getResources("classpath*:rag/*.txt");
            org.springframework.core.io.Resource[] globalMd = resolver.getResources("classpath*:rag/*.md");
            // 角色目录新结构：personas/{roleCode}/lore/**/*.md|*.txt + personas/{roleCode}/memory_cards.jsonl
            // lore 下面允许再按 stories/、profile/ 等子目录组织；递归扫描保证剧情切片真正进入 RAG。
            org.springframework.core.io.Resource[] roleTxt = resolver.getResources("classpath*:personas/*/lore/**/*.txt");
            org.springframework.core.io.Resource[] roleMd = resolver.getResources("classpath*:personas/*/lore/**/*.md");
            org.springframework.core.io.Resource[] roleJsonl = resolver.getResources("classpath*:personas/*/memory_cards.jsonl");

            List<org.springframework.core.io.Resource> textResources = new ArrayList<>();
            textResources.addAll(List.of(globalTxt));
            textResources.addAll(List.of(globalMd));
            textResources.addAll(List.of(roleTxt));
            textResources.addAll(List.of(roleMd));

            List<TextSegment> segmentsToEmbed = new ArrayList<>();
            List<RagChunk> tempChunks = new ArrayList<>();

            // Step 1.a: 加载文本类资源并按滑窗切块
            for (org.springframework.core.io.Resource resource : textResources) {
                String filename = resource.getFilename() == null ? "unknown" : resource.getFilename();
                // 角色目录下的语料 source 必须带 roleCode 前缀（如 shu_profile.md），否则 retrieveContext 的 prefix 过滤会全过滤掉
                String roleCode = resolveRoleCodeFromPath(resource);
                String source = roleCode == null ? filename : roleCode + "_" + filename;
                String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String normalized = normalizeText(text);
                if (!StringUtils.hasText(normalized)) {
                    continue;
                }

                int step = Math.max(1, chunkSize - chunkOverlap);
                int chunkIndex = 0;
                for (int start = 0; start < normalized.length(); start += step) {
                    int end = Math.min(normalized.length(), start + chunkSize);
                    String chunkText = normalized.substring(start, end).trim();
                    if (!StringUtils.hasText(chunkText)) {
                        continue;
                    }
                    Set<String> terms = extractTerms(chunkText);
                    tempChunks.add(new RagChunk(source, chunkIndex++, chunkText, terms, null));
                    segmentsToEmbed.add(TextSegment.from(chunkText));
                    if (end >= normalized.length()) {
                        break;
                    }
                }
            }

            // Step 1.b: 加载 memory_cards.jsonl —— 一行一条记忆卡，单条直接作为一个 chunk
            //          source 命名形如 "shu_memory_cards.jsonl"，便于按角色隔离与权重识别
            for (org.springframework.core.io.Resource resource : roleJsonl) {
                String filename = resource.getFilename() == null ? "unknown.jsonl" : resource.getFilename();
                String roleCode = resolveRoleCodeFromPath(resource);
                // 把 source 强制带上 roleCode 前缀，复用现有的 prefix 过滤逻辑
                String source = (roleCode == null ? "" : roleCode + "_") + filename;

                String raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int idx = 0;
                for (String line : raw.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    String cardText = parseMemoryCardLine(trimmed);
                    if (!StringUtils.hasText(cardText)) {
                        continue;
                    }
                    Set<String> terms = extractTerms(cardText);
                    tempChunks.add(new RagChunk(source, idx++, cardText, terms, null));
                    segmentsToEmbed.add(TextSegment.from(cardText));
                }
                log.info("加载记忆卡片资源: {} -> {} 条 (roleCode={})", filename, idx, roleCode);
            }

            if (tempChunks.isEmpty()) {
                chunks = List.of();
                return 0;
            }

            // Step 2: 批量计算向量
            log.info("开始向向量化 {} 个分块...", tempChunks.size());
            List<Embedding> embeddings = embeddingModel.embedAll(segmentsToEmbed).content();
            log.info("向量化完成");

            // Step 3: 组装 RagChunk with vectors
            for (int i = 0; i < tempChunks.size(); i++) {
                RagChunk oldChunk = tempChunks.get(i);
                float[] vec = embeddings.get(i).vector();
                rebuilt.add(new RagChunk(oldChunk.source(), oldChunk.chunkIndex(), oldChunk.text(), oldChunk.terms(), vec));
            }
        } catch (IOException e) {
            log.error("加载 RAG 知识库失败", e);
        }

        chunks = List.copyOf(rebuilt);
        saveToRedis(rebuilt);
        return chunks.size();
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.replace("\r", "\n").replace("\t", " ").trim();
    }

    private Set<String> extractTerms(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }

        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> terms = new HashSet<>();

        // 英文/数字 token
        for (String token : lower.split("[^a-z0-9]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }

        // 中文按单字分词
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                terms.add(String.valueOf(c));
            }
        }

        return terms;
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private record ScoredChunk(RagChunk chunk, float score) {
    }

    /**
     * 判断分块是否来自 memory_cards.jsonl（用于检索权重提升）。
     */
    private boolean isMemoryCard(RagChunk chunk) {
        return chunk != null
                && chunk.source() != null
                && chunk.source().toLowerCase(Locale.ROOT).endsWith("memory_cards.jsonl");
    }

    /**
     * 解析 memory_cards.jsonl 单行（容错：失败时整行回退为纯文本 chunk）。
     * 期望格式：{"type":"...","content":"...","keywords":[...]}
     * 检索文本 = type + content + keywords，最大化召回率。
     */
    private String parseMemoryCardLine(String line) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(line);
            String type = node.path("type").asText("");
            String content = node.path("content").asText("");
            if (!StringUtils.hasText(content)) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            if (StringUtils.hasText(type)) {
                sb.append("[").append(type).append("] ");
            }
            sb.append(content);
            com.fasterxml.jackson.databind.JsonNode kws = node.path("keywords");
            if (kws.isArray() && !kws.isEmpty()) {
                sb.append(" 关键词:");
                for (com.fasterxml.jackson.databind.JsonNode kw : kws) {
                    sb.append(kw.asText("")).append(" ");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("解析 memory_cards 行失败，按纯文本回退: {}", e.getMessage());
            return line;
        }
    }

    /**
     * 从 classpath 资源 URI 推断 roleCode：
     *  - 优先匹配 personas/{roleCode}/...
     *  - 兼容旧路径 rag/{roleCode}/...
     *  - 无角色子目录（顶层 rag/*.md 类全局语料）返回 null
     */
    private String resolveRoleCodeFromPath(org.springframework.core.io.Resource resource) {
        try {
            String uri = resource.getURI().toString();
            String[] markers = {"/personas/", "/rag/"};
            for (String marker : markers) {
                int idx = uri.indexOf(marker);
                if (idx < 0) continue;
                String tail = uri.substring(idx + marker.length());
                int slash = tail.indexOf('/');
                if (slash <= 0) continue;
                return tail.substring(0, slash).toLowerCase(Locale.ROOT);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void addLongTermMemory(Integer userId, Integer roleId, String summaryText) {
        if (!StringUtils.hasText(summaryText)) return;
        try {
            Embedding embedding = embeddingModel.embed(summaryText).content();
            Set<String> terms = extractTerms(summaryText);
            RagChunk chunk = new RagChunk("memory", 0, summaryText, terms, embedding.vector());
            
            String redisKey = "rag:memory:" + userId + ":" + roleId;
            String chunkJson = objectMapper.writeValueAsString(chunk);
            stringRedisTemplate.opsForHash().put(redisKey, String.valueOf(System.currentTimeMillis()), chunkJson);
            log.info("成功为 User={} Role={} 存入长期记忆: {}", userId, roleId, summaryText);
        } catch (Exception e) {
            log.error("保存长期记忆到Redis失败", e);
        }
    }

    @Override
    public String searchLongTermMemoryContext(Integer userId, Integer roleId, String query, int topK) {
        if (!StringUtils.hasText(query)) return "";
        try {
            String redisKey = "rag:memory:" + userId + ":" + roleId;
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(redisKey);
            if (entries.isEmpty()) return "";

            List<RagChunk> memoryChunks = new ArrayList<>();
            for (Object value : entries.values()) {
                RagChunk chunk = objectMapper.readValue((String) value, RagChunk.class);
                memoryChunks.add(chunk);
            }

            Embedding queryEmb = embeddingModel.embed(query).content();
            memoryChunks.sort(Comparator.comparingDouble((RagChunk c) -> 
                    -cosineSimilarity(queryEmb.vector(), c.embedding())));

            StringBuilder context = new StringBuilder();
            for (int i = 0; i < Math.min(topK, memoryChunks.size()); i++) {
                context.append("- ").append(memoryChunks.get(i).text()).append("\n");
            }
            return context.toString();
        } catch (Exception e) {
            log.error("检索长期记忆失败", e);
            return "";
        }
    }
}
