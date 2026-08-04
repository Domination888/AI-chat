package org.example.aichat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.config.RuntimeConfig;
import org.example.aichat.config.RuntimeConfigService;
import org.example.aichat.dto.History;
import org.example.aichat.dto.ProactiveCandidate;
import org.example.aichat.dto.ProactiveInterest;
import org.example.aichat.mapper.HistoryMapper;
import org.example.aichat.mapper.ProactiveCandidateMapper;
import org.example.aichat.mapper.ProactiveInterestMapper;
import org.example.aichat.search.SearchRequest;
import org.example.aichat.search.SearchResponse;
import org.example.aichat.search.SearchSource;
import org.example.aichat.search.WebSearchGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProactiveResearchService {
    private final WebSearchGateway webSearchGateway;
    private final ProactiveInterestMapper interestMapper;
    private final ProactiveCandidateMapper candidateMapper;
    private final HistoryMapper historyMapper;
    private final InterestDiscoveryService interestDiscoveryService;
    private final RuntimeConfigService runtimeConfigService;
    private final ObjectProvider<ProactiveChatService> proactiveChatServiceProvider;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "proactive-research-scheduler"); t.setDaemon(true); return t;
    });
    private final ExecutorService researchExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "proactive-research-worker"); t.setDaemon(true); return t;
    });
    private final Map<Integer, AtomicInteger> newMessageCounts = new ConcurrentHashMap<>();
    private final Map<Integer, LocalDateTime> lastResearchAttempts = new ConcurrentHashMap<>();
    private final Map<Integer, LocalDateTime> lastInterestRefreshAttempts = new ConcurrentHashMap<>();
    private final Set<Integer> runningUsers = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::safeTick, 30, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        researchExecutor.shutdownNow();
    }

    public void onUserMessage(Integer userId, String message) {
        if (userId == null || message == null || message.isBlank()) return;
        int count = newMessageCounts.computeIfAbsent(userId, unused -> new AtomicInteger()).incrementAndGet();
        if (count >= 5) {
            newMessageCounts.get(userId).set(0);
            researchExecutor.execute(() -> refreshInterests(userId));
        }
    }

    public List<ProactiveInterest> listInterests(Integer userId) {
        return interestMapper.findByUserId(userId);
    }

    public ProactiveInterest addManualInterest(Integer userId, String topic) {
        ProactiveInterest interest = new ProactiveInterest();
        interest.setUserId(userId);
        interest.setTopic(sanitizeTopic(topic));
        interest.setSource("manual");
        interest.setWeight(0.8);
        interest.setEnabled(true);
        interest.setEvidence("用户手动添加");
        interest.setLastInferredAt(LocalDateTime.now());
        interestMapper.upsert(interest);
        return interest;
    }

    public boolean updateInterest(ProactiveInterest interest) {
        interest.setTopic(sanitizeTopic(interest.getTopic()));
        if (interest.getWeight() == null) interest.setWeight(0.5);
        if (interest.getEnabled() == null) interest.setEnabled(true);
        return interestMapper.update(interest) > 0;
    }

    public boolean deleteInterest(Integer userId, Long id) {
        return interestMapper.delete(id, userId) > 0;
    }

    public boolean feedback(Integer userId, Long candidateId, String feedback) {
        if (!Set.of("interested", "less_like").contains(feedback)) return false;
        ProactiveCandidate candidate = candidateMapper.findById(candidateId);
        if (candidate == null || !userId.equals(candidate.getUserId())) return false;
        if (candidateMapper.saveFeedback(candidateId, userId, feedback) == 0) return false;
        if ("interested".equals(feedback)) interestMapper.markInterested(userId, candidate.getTopic());
        else interestMapper.markLessLike(userId, candidate.getTopic());
        return true;
    }

    public List<ProactiveCandidate> deliveredForConversation(String conversationId) {
        return candidateMapper.findDeliveredByConversation(conversationId);
    }

    public boolean runNow(Integer userId) {
        if (!runningUsers.add(userId)) return false;
        researchExecutor.execute(() -> {
            try { researchUser(userId); }
            finally { runningUsers.remove(userId); }
        });
        return true;
    }

    public boolean enqueueExternalCandidate(Integer userId, String topic, String title, String summary,
                                            String reason, List<SearchSource> sources, double score) {
        if (userId == null || sources == null || sources.isEmpty() || score < scoreThreshold()) return false;
        try {
            SearchSource first = sources.get(0);
            String fingerprint = sha256(canonicalUrl(first.getUrl()) + "|" + normalizeTitle(first.getTitle()));
            if (candidateMapper.countRecentFingerprint(userId, fingerprint) > 0) return false;
            ProactiveCandidate candidate = new ProactiveCandidate();
            candidate.setUserId(userId);
            candidate.setTopic(sanitizeTopic(topic));
            candidate.setTitle(title == null || title.isBlank() ? first.getTitle() : title);
            candidate.setSummary(summary == null ? "" : summary);
            candidate.setReason(reason == null ? "" : reason);
            candidate.setSourcesJson(objectMapper.writeValueAsString(sources.stream().limit(3).toList()));
            candidate.setScore(score);
            candidate.setFingerprint(fingerprint);
            candidate.setStatus("pending");
            candidate.setExpiresAt(LocalDateTime.now().plusHours(24));
            candidateMapper.insert(candidate);
            return true;
        } catch (Exception e) {
            log.warn("外部主动候选入队失败 userId={}, title={}: {}", userId, title, e.getMessage());
            return false;
        }
    }

    public void refreshInterests(Integer userId) {
        lastInterestRefreshAttempts.put(userId, LocalDateTime.now());
        try {
            List<String> messages = historyMapper.findRecentUserMessagesByUserId(userId, 30).stream()
                    .map(History::getContent).filter(s -> s != null && !s.isBlank()).toList();
            for (InterestDiscoveryService.Suggestion suggestion : interestDiscoveryService.discover(messages)) {
                ProactiveInterest interest = new ProactiveInterest();
                interest.setUserId(userId);
                interest.setTopic(sanitizeTopic(suggestion.topic()));
                interest.setSource("inferred");
                interest.setWeight(suggestion.weight());
                interest.setEnabled(true);
                interest.setEvidence(suggestion.evidence());
                interest.setLastInferredAt(LocalDateTime.now());
                if (!interest.getTopic().isBlank()) interestMapper.upsert(interest);
            }
            trimInferredInterests(userId);
        } catch (Exception e) {
            log.warn("刷新用户兴趣失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private void safeTick() {
        try {
            candidateMapper.expirePending();
            ProactiveChatService proactive = proactiveChatServiceProvider.getIfAvailable();
            if (proactive == null) return;
            Map<Integer, ProactiveChatService.ActiveTarget> targets = proactive.activeTargets().stream()
                    .collect(java.util.stream.Collectors.toMap(ProactiveChatService.ActiveTarget::userId, t -> t,
                            (a, b) -> a.lastInteractionAt() >= b.lastInteractionAt() ? a : b));
            for (ProactiveChatService.ActiveTarget target : targets.values()) {
                if (!enabled()) continue;
                maybeScheduleResearch(target.userId());
            }
        } catch (Exception e) {
            log.warn("主动研究调度失败: {}", e.getMessage());
        }
    }

    /**
     * 为一次已经确认“旧话题结束”的主动触发同步准备新话题。
     * 优先消费后台已经准备好的候选；队列为空时只研究权重最高的一个兴趣，
     * 将单次点击/定时决策的联网耗时限制在一轮 Search-RAG 内。
     */
    public Optional<PreparedTopic> prepareTopicNow(Integer userId) {
        if (userId == null) return Optional.empty();
        candidateMapper.expirePending();

        ProactiveCandidate pending = candidateMapper.findBestPending(userId);
        if (pending != null) {
            Optional<PreparedTopic> prepared = toPreparedTopic(pending);
            if (prepared.isPresent()) return prepared;
        }

        List<ProactiveInterest> allInterests = interestMapper.findByUserId(userId);
        if (needsInterestRefresh(userId, allInterests)) {
            refreshInterests(userId);
        }
        List<ProactiveInterest> interests = interestMapper.findActive(userId, 1);
        ProactiveInterest interest = interests.isEmpty() ? defaultDiscoveryInterest(userId) : interests.get(0);
        try {
            SearchResponse response = webSearchGateway.search(SearchRequest.builder()
                    .query(interest.getTopic()).maxSources(3).build());
            if (!response.hasSources() || response.getSources().stream().noneMatch(SearchSource::isPageRead)) {
                log.info("即时主动话题没有成功读取的来源 userId={}, topic={}, status={}, diagnostics={}",
                        userId, interest.getTopic(), response.getStatus(), response.getDiagnostics());
                return Optional.empty();
            }
            ProactiveCandidate candidate = buildCandidate(userId, interest, response);
            if (candidate.getScore() < scoreThreshold()) {
                log.info("即时主动话题评分未达阈值 userId={}, topic={}, score={}, threshold={}",
                        userId, interest.getTopic(), candidate.getScore(), scoreThreshold());
                return Optional.empty();
            }
            if (candidateMapper.countRecentFingerprint(userId, candidate.getFingerprint()) > 0) return Optional.empty();
            candidateMapper.insert(candidate);
            return toPreparedTopic(candidate);
        } catch (Exception e) {
            log.warn("即时主动话题搜索失败 userId={}, topic={}: {}", userId, interest.getTopic(), e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isQuietHoursNow() {
        return isQuietHours();
    }

    public boolean isResearchedTopicCooldownElapsed(Integer userId) {
        LocalDateTime lastDelivered = candidateMapper.findLastDeliveredAt(userId);
        return lastDelivered == null
                || Duration.between(lastDelivered, LocalDateTime.now()).toMinutes() >= cooldownMinutes();
    }

    private Optional<PreparedTopic> toPreparedTopic(ProactiveCandidate candidate) {
        try {
            List<SearchSource> sources = objectMapper.readValue(candidate.getSourcesJson(), new TypeReference<>() {});
            if (sources == null || sources.isEmpty()) return Optional.empty();
            return Optional.of(new PreparedTopic(candidate.getId(), candidate.getTitle(), candidate.getReason(),
                    sources, buildPrompt(candidate, sources)));
        } catch (Exception e) {
            log.warn("主动候选来源解析失败 candidateId={}: {}", candidate.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private void maybeScheduleResearch(Integer userId) {
        LocalDateTime last = candidateMapper.findLastCreatedAt(userId);
        LocalDateTime attempted = lastResearchAttempts.get(userId);
        if (attempted != null && (last == null || attempted.isAfter(last))) last = attempted;
        if (last != null && Duration.between(last, LocalDateTime.now()).toMinutes() < researchIntervalMinutes()) return;
        if (!runningUsers.add(userId)) return;
        researchExecutor.execute(() -> {
            try { researchUser(userId); }
            finally { runningUsers.remove(userId); }
        });
    }

    private void researchUser(Integer userId) {
        lastResearchAttempts.put(userId, LocalDateTime.now());
        List<ProactiveInterest> allInterests = interestMapper.findByUserId(userId);
        if (needsInterestRefresh(userId, allInterests)) {
            refreshInterests(userId);
        }
        List<ProactiveInterest> interests = interestMapper.findActive(userId, 3);
        for (ProactiveInterest interest : interests) {
            try {
                SearchResponse response = webSearchGateway.search(SearchRequest.builder()
                        .query(interest.getTopic()).maxSources(3).build());
                if (!response.hasSources() || response.getSources().stream().noneMatch(SearchSource::isPageRead)) continue;
                ProactiveCandidate candidate = buildCandidate(userId, interest, response);
                if (candidate.getScore() < scoreThreshold()) continue;
                if (candidateMapper.countRecentFingerprint(userId, candidate.getFingerprint()) > 0) continue;
                candidateMapper.insert(candidate);
            } catch (Exception e) {
                log.debug("主动主题搜索失败 userId={}, topic={}: {}", userId, interest.getTopic(), e.getMessage());
            }
        }
    }

    private ProactiveCandidate buildCandidate(Integer userId, ProactiveInterest interest, SearchResponse response) throws Exception {
        List<SearchSource> sources = prioritizeUnusedSource(userId, response.getSources());
        SearchSource first = sources.get(0);
        double score = calculateCandidateScore(first);
        String summary = first.getExcerpts() == null || first.getExcerpts().isEmpty()
                ? first.getSnippet() : first.getExcerpts().get(0);
        if (summary == null) summary = "";
        if (summary.length() > 800) summary = summary.substring(0, 800);
        String fingerprintMaterial = canonicalUrl(first.getUrl()) + "|" + normalizeTitle(first.getTitle());

        ProactiveCandidate candidate = new ProactiveCandidate();
        candidate.setUserId(userId);
        candidate.setTopic(interest.getTopic());
        candidate.setTitle(first.getTitle());
        candidate.setSummary(summary);
        candidate.setReason("因为你关注“" + interest.getTopic() + "”");
        candidate.setSourcesJson(objectMapper.writeValueAsString(sources));
        candidate.setScore(score);
        candidate.setFingerprint(sha256(fingerprintMaterial));
        candidate.setStatus("pending");
        candidate.setExpiresAt(LocalDateTime.now().plusHours(24));
        return candidate;
    }

    private List<SearchSource> prioritizeUnusedSource(Integer userId, List<SearchSource> original) throws Exception {
        List<SearchSource> sources = new ArrayList<>(original);
        int preferred = findUnusedSourceIndex(userId, sources, true);
        if (preferred < 0) preferred = findUnusedSourceIndex(userId, sources, false);
        if (preferred > 0) {
            SearchSource source = sources.remove(preferred);
            sources.add(0, source);
        }
        return sources;
    }

    private int findUnusedSourceIndex(Integer userId, List<SearchSource> sources, boolean requirePageRead) throws Exception {
        for (int i = 0; i < sources.size(); i++) {
            SearchSource source = sources.get(i);
            if (requirePageRead && !source.isPageRead()) continue;
            String fingerprint = sha256(canonicalUrl(source.getUrl()) + "|" + normalizeTitle(source.getTitle()));
            if (candidateMapper.countRecentFingerprint(userId, fingerprint) == 0) return i;
        }
        return -1;
    }

    static double calculateCandidateScore(SearchSource primarySource) {
        double relevance = Math.max(0.0, Math.min(1.0, primarySource.getScore()));
        double credibility = primarySource.isPageRead() ? 0.95 : 0.45;
        double chatability = primarySource.getExcerpts() != null && !primarySource.getExcerpts().isEmpty() ? 0.9 : 0.6;
        return 100.0 * (0.35 * relevance + 0.30 + 0.20 * credibility + 0.15 * chatability);
    }

    private ProactiveInterest defaultDiscoveryInterest(Integer userId) {
        ProactiveInterest interest = new ProactiveInterest();
        interest.setUserId(userId);
        interest.setTopic("近期值得聊的科技与生活新发现");
        interest.setSource("fallback");
        interest.setWeight(0.4);
        interest.setEnabled(true);
        interest.setEvidence("尚未形成明确兴趣时使用的通用话题");
        return interest;
    }

    private String buildPrompt(ProactiveCandidate candidate, List<SearchSource> sources) {
        StringBuilder sb = new StringBuilder("[System: 你刚为用户发现了一条与其兴趣相关的新信息。请用角色口吻自然地用1-3句话主动开场，说明为什么想到用户；不要长篇播报，等待用户追问。只能依据下列来源，不要编造。]\n");
        sb.append("兴趣：").append(candidate.getTopic()).append("\n标题：").append(candidate.getTitle()).append("\n");
        if (candidate.getSummary() != null && !candidate.getSummary().isBlank()) {
            String summary = candidate.getSummary();
            sb.append("摘要：").append(summary, 0, Math.min(summary.length(), 1800)).append("\n");
        }
        int i = 1;
        for (SearchSource source : sources) {
            sb.append("[").append(i++).append("] ").append(source.getTitle()).append(" ").append(source.getUrl()).append("\n");
            if (source.getExcerpts() != null && !source.getExcerpts().isEmpty()) sb.append(source.getExcerpts().get(0)).append("\n");
        }
        return sb.toString();
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(client().getAutoResearchEnabled());
    }
    private int researchIntervalMinutes() { return positive(client().getResearchIntervalMinutes(), 180); }
    private int cooldownMinutes() { return positive(client().getResearchCooldownMinutes(), 180); }
    private int scoreThreshold() { return positive(client().getResearchScoreThreshold(), 80); }
    private RuntimeConfig.ClientSection client() { return runtimeConfigService.getEffective().getClient(); }

    private boolean isQuietHours() {
        LocalTime now = LocalTime.now();
        LocalTime start = parseTime(client().getResearchQuietStart(), LocalTime.of(23, 0));
        LocalTime end = parseTime(client().getResearchQuietEnd(), LocalTime.of(9, 0));
        if (start.equals(end)) return false;
        return isWithinQuietHours(now, start, end);
    }

    static boolean isWithinQuietHours(LocalTime now, LocalTime start, LocalTime end) {
        if (start.equals(end)) return false;
        return start.isBefore(end) ? !now.isBefore(start) && now.isBefore(end)
                : !now.isBefore(start) || now.isBefore(end);
    }

    private boolean needsInterestRefresh(Integer userId, List<ProactiveInterest> interests) {
        LocalDateTime attempted = lastInterestRefreshAttempts.get(userId);
        if (attempted != null && attempted.isAfter(LocalDateTime.now().minusHours(24))) return false;
        LocalDateTime newestInference = interests.stream()
                .filter(i -> "inferred".equals(i.getSource()))
                .map(ProactiveInterest::getLastInferredAt).filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo).orElse(null);
        return newestInference == null || newestInference.isBefore(LocalDateTime.now().minusHours(24));
    }

    private void trimInferredInterests(Integer userId) {
        List<ProactiveInterest> all = interestMapper.findByUserId(userId);
        long manualCount = all.stream().filter(i -> "manual".equals(i.getSource())).count();
        long inferredSlots = Math.max(0, 12 - manualCount);
        all.stream().filter(i -> "inferred".equals(i.getSource())).skip(inferredSlots)
                .forEach(i -> interestMapper.delete(i.getId(), userId));
    }

    private static LocalTime parseTime(String value, LocalTime fallback) {
        try { return value == null || value.isBlank() ? fallback : LocalTime.parse(value); }
        catch (Exception e) { return fallback; }
    }
    private static int positive(Integer value, int fallback) { return value == null || value <= 0 ? fallback : value; }
    private static String sanitizeTopic(String topic) {
        if (topic == null) return "";
        String value = topic.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").strip();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
    private static String canonicalUrl(String value) {
        try {
            URI uri = URI.create(value);
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath().replaceAll("/$", "");
            return (uri.getScheme() + "://" + uri.getHost() + path).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return value == null ? "" : value.replaceFirst("[?#].*$", "").toLowerCase(Locale.ROOT);
        }
    }
    private static String normalizeTitle(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }
    private static String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public record PreparedTopic(Long candidateId, String title, String reason,
                                List<SearchSource> sources, String prompt) {
    }
}
