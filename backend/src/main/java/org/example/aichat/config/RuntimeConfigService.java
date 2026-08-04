package org.example.aichat.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.service.impl.VoiceServiceImpl;
import org.example.aichat.service.memos.MemosClient;
import org.example.aichat.search.SearchProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 全链路运行时配置：启动时从 JSON 覆盖 yml，运行中支持前端热更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeConfigService {

    private final RuntimeConfigStore store;
    private final LlmProperties llmProperties;
    private final EmbeddingProperties embeddingProperties;
    private final VoiceProperties voiceProperties;
    private final MemosProperties memosProperties;
    private final EmbeddingModelHolder embeddingModelHolder;
    private final VoiceServiceImpl voiceService;
    private final MemosClient memosClient;
    private final SearchProperties searchProperties;

    private RuntimeConfig ymlSnapshot;
    private RuntimeConfig.ClientSection clientSection = new RuntimeConfig.ClientSection();

    @PostConstruct
    public void captureYmlDefaults() {
        ymlSnapshot = snapshotFromProperties();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadPersistedOverrides() {
        store.load().ifPresent(saved -> {
            log.info("从 runtime-config.json 加载运行时覆盖");
            if (saved.getClient() != null) {
                clientSection = copySection(saved.getClient(), RuntimeConfig.ClientSection.class);
            }
            applyMerge(saved, true);
        });
    }

    public RuntimeConfig getEffective() {
        RuntimeConfig cfg = snapshotFromProperties();
        cfg.setClient(copySection(clientSection, RuntimeConfig.ClientSection.class));
        return cfg;
    }

    /**
     * 合并更新并持久化；立即对所有后续请求生效。
     */
    public RuntimeConfig update(RuntimeConfig patch) {
        RuntimeConfig current = getEffective();
        RuntimeConfig merged = merge(current, patch);
        if (merged.getClient() != null) {
            clientSection = copySection(merged.getClient(), RuntimeConfig.ClientSection.class);
        }
        applyMerge(merged, true);
        store.save(merged);
        return getEffective();
    }

    public RuntimeConfig getYmlDefaults() {
        return ymlSnapshot;
    }

    private RuntimeConfig snapshotFromProperties() {
        RuntimeConfig cfg = new RuntimeConfig();

        RuntimeConfig.LlmSection llm = new RuntimeConfig.LlmSection();
        llm.setBaseUrl(llmProperties.getBaseUrl());
        llm.setModelName(llmProperties.getModelName());
        llm.setApiKey(llmProperties.getApiKey());
        llm.setThinkingMode(llmProperties.getEffectiveThinkingMode());
        llm.setReasoningEffort(llmProperties.getEffectiveReasoningEffort());
        llm.setUtilityInheritConnection(llmProperties.isUtilityInheritConnection());
        llm.setUtilityBaseUrl(llmProperties.getUtilityBaseUrl());
        llm.setUtilityApiKey(llmProperties.getUtilityApiKey());
        llm.setUtilityModelName(llmProperties.getUtilityModelName());
        llm.setUtilityThinkingMode(llmProperties.getEffectiveUtilityThinkingMode());
        llm.setUtilityReasoningEffort(llmProperties.getEffectiveUtilityReasoningEffort());
        llm.setStreamingModelName(llmProperties.getStreamingModelName());
        llm.setConnectTimeoutMs(llmProperties.getConnectTimeoutMs());
        llm.setReadTimeoutMs(llmProperties.getReadTimeoutMs());
        llm.setMaxRetries(llmProperties.getMaxRetries());
        cfg.setLlm(llm);

        RuntimeConfig.EmbeddingSection emb = new RuntimeConfig.EmbeddingSection();
        emb.setBaseUrl(embeddingProperties.getBaseUrl());
        emb.setModelName(embeddingProperties.getModelName());
        emb.setApiKey(embeddingProperties.getApiKey());
        cfg.setEmbedding(emb);

        RuntimeConfig.VoiceSection voice = new RuntimeConfig.VoiceSection();
        voice.setAsrUrl(voiceProperties.getAsrUrl());
        voice.setAsrLanguage(voiceProperties.getAsrLanguage());
        voice.setAsrTimeoutMs(voiceProperties.getAsrTimeoutMs());
        voice.setTtsEngine(voiceProperties.getTtsEngine());
        voice.setAstraTtsBaseUrl(voiceProperties.getAstraTtsBaseUrl());
        voice.setAstraDefaultAvatarId(voiceProperties.getAstraDefaultAvatarId());
        voice.setAstraStreamingChunkSize(voiceProperties.getAstraStreamingChunkSize());
        voice.setTtsTimeoutMs(voiceProperties.getTtsTimeoutMs());
        voice.setTtsDefaultProfile(voiceProperties.getTtsDefaultProfile());
        cfg.setVoice(voice);

        RuntimeConfig.MemosSection memos = new RuntimeConfig.MemosSection();
        memos.setEnabled(memosProperties.isEnabled());
        memos.setBaseUrl(memosProperties.getBaseUrl());
        memos.setSearchTopK(memosProperties.getSearchTopK());
        memos.setSearchMode(memosProperties.getSearchMode());
        memos.setRelativity(memosProperties.getRelativity());
        memos.setIncludePreference(memosProperties.isIncludePreference());
        memos.setPrefTopK(memosProperties.getPrefTopK());
        memos.setDedup(memosProperties.getDedup());
        memos.setSearchToolMemory(memosProperties.isSearchToolMemory());
        memos.setIncludeSkillMemory(memosProperties.isIncludeSkillMemory());
        memos.setSaveAssistantTurns(memosProperties.isSaveAssistantTurns());
        memos.setFallbackToRag(memosProperties.isFallbackToRag());
        memos.setModelInheritConnection(memosProperties.isModelInheritConnection());
        memos.setModelBaseUrl(memosProperties.getModelBaseUrl());
        memos.setModelApiKey(memosProperties.getModelApiKey());
        memos.setModelName(memosProperties.getModelName());
        memos.setEmbeddingInheritConnection(memosProperties.isEmbeddingInheritConnection());
        memos.setEmbeddingBaseUrl(memosProperties.getEmbeddingBaseUrl());
        memos.setEmbeddingApiKey(memosProperties.getEmbeddingApiKey());
        memos.setEmbeddingModelName(memosProperties.getEmbeddingModelName());
        memos.setEmbeddingDimension(memosProperties.getEmbeddingDimension());
        cfg.setMemos(memos);

        RuntimeConfig.SearchSection search = new RuntimeConfig.SearchSection();
        search.setSearxngUrl(searchProperties.getSearxngUrl());
        search.setQueryPlannerEnabled(searchProperties.isQueryPlannerEnabled());
        search.setPlannerTimeoutMs(searchProperties.getPlannerTimeoutMs());
        search.setMaxQueries(searchProperties.getMaxQueries());
        search.setResultsPerQuery(searchProperties.getResultsPerQuery());
        search.setFetchPages(searchProperties.getFetchPages());
        search.setMaxSources(searchProperties.getMaxSources());
        search.setPageTimeoutMs(searchProperties.getPageTimeoutMs());
        search.setTotalTimeoutMs(searchProperties.getTotalTimeoutMs());
        search.setResultCacheMinutes(searchProperties.getResultCacheMinutes());
        search.setPageCacheHours(searchProperties.getPageCacheHours());
        search.setEngines(searchProperties.getEngines());
        cfg.setSearch(search);

        return cfg;
    }

    private void applyMerge(RuntimeConfig cfg, boolean refreshClients) {
        if (cfg.getLlm() != null) {
            applyLlm(cfg.getLlm());
        }
        if (cfg.getEmbedding() != null) {
            applyEmbedding(cfg.getEmbedding());
        }
        if (cfg.getVoice() != null) {
            applyVoice(cfg.getVoice());
        }
        if (cfg.getMemos() != null) {
            applyMemos(cfg.getMemos());
        }
        if (cfg.getSearch() != null) {
            applySearch(cfg.getSearch());
        }
        if (refreshClients) {
            embeddingModelHolder.refresh();
            voiceService.refreshAsrClient();
            memosClient.refreshClient();
            log.info("运行时配置已热更新");
        }
    }

    private void applyLlm(RuntimeConfig.LlmSection s) {
        if (StringUtils.hasText(s.getBaseUrl())) llmProperties.setBaseUrl(s.getBaseUrl().trim());
        if (StringUtils.hasText(s.getModelName())) llmProperties.setModelName(s.getModelName().trim());
        // 空字符串是有效值：用于从外部 API 切回无需鉴权的本地模型。
        if (s.getApiKey() != null) llmProperties.setApiKey(s.getApiKey().trim());
        if (StringUtils.hasText(s.getThinkingMode())) llmProperties.setThinkingMode(s.getThinkingMode().trim());
        if (StringUtils.hasText(s.getReasoningEffort())) llmProperties.setReasoningEffort(s.getReasoningEffort().trim());
        if (s.getUtilityInheritConnection() != null) {
            llmProperties.setUtilityInheritConnection(s.getUtilityInheritConnection());
        }
        if (s.getUtilityBaseUrl() != null) llmProperties.setUtilityBaseUrl(s.getUtilityBaseUrl().trim());
        if (s.getUtilityApiKey() != null) llmProperties.setUtilityApiKey(s.getUtilityApiKey().trim());
        if (s.getUtilityModelName() != null) llmProperties.setUtilityModelName(s.getUtilityModelName().trim());
        if (StringUtils.hasText(s.getUtilityThinkingMode())) {
            llmProperties.setUtilityThinkingMode(s.getUtilityThinkingMode().trim());
        }
        if (StringUtils.hasText(s.getUtilityReasoningEffort())) {
            llmProperties.setUtilityReasoningEffort(s.getUtilityReasoningEffort().trim());
        }
        if (StringUtils.hasText(s.getStreamingModelName())) {
            llmProperties.setStreamingModelName(s.getStreamingModelName().trim());
        }
        if (s.getConnectTimeoutMs() != null) llmProperties.setConnectTimeoutMs(s.getConnectTimeoutMs());
        if (s.getReadTimeoutMs() != null) llmProperties.setReadTimeoutMs(s.getReadTimeoutMs());
        if (s.getMaxRetries() != null) llmProperties.setMaxRetries(s.getMaxRetries());
    }

    private void applyEmbedding(RuntimeConfig.EmbeddingSection s) {
        if (StringUtils.hasText(s.getBaseUrl())) embeddingProperties.setBaseUrl(s.getBaseUrl().trim());
        if (StringUtils.hasText(s.getModelName())) embeddingProperties.setModelName(s.getModelName().trim());
        if (s.getApiKey() != null) embeddingProperties.setApiKey(s.getApiKey().trim());
    }

    private void applyVoice(RuntimeConfig.VoiceSection s) {
        if (StringUtils.hasText(s.getAsrUrl())) voiceProperties.setAsrUrl(s.getAsrUrl().trim());
        if (StringUtils.hasText(s.getAsrLanguage())) voiceProperties.setAsrLanguage(s.getAsrLanguage().trim());
        if (s.getAsrTimeoutMs() != null) voiceProperties.setAsrTimeoutMs(s.getAsrTimeoutMs());
        if (StringUtils.hasText(s.getTtsEngine())) voiceProperties.setTtsEngine(s.getTtsEngine().trim());
        if (StringUtils.hasText(s.getAstraTtsBaseUrl())) {
            voiceProperties.setAstraTtsBaseUrl(s.getAstraTtsBaseUrl().trim());
        }
        if (s.getAstraDefaultAvatarId() != null) {
            voiceProperties.setAstraDefaultAvatarId(s.getAstraDefaultAvatarId().trim());
        }
        if (s.getAstraStreamingChunkSize() != null) {
            voiceProperties.setAstraStreamingChunkSize(s.getAstraStreamingChunkSize());
        }
        if (s.getTtsTimeoutMs() != null) voiceProperties.setTtsTimeoutMs(s.getTtsTimeoutMs());
        if (StringUtils.hasText(s.getTtsDefaultProfile())) {
            voiceProperties.setTtsDefaultProfile(s.getTtsDefaultProfile().trim());
        }
    }

    private void applyMemos(RuntimeConfig.MemosSection s) {
        if (s.getEnabled() != null) memosProperties.setEnabled(s.getEnabled());
        if (StringUtils.hasText(s.getBaseUrl())) memosProperties.setBaseUrl(s.getBaseUrl().trim());
        if (s.getSearchTopK() != null) memosProperties.setSearchTopK(s.getSearchTopK());
        if (StringUtils.hasText(s.getSearchMode())) memosProperties.setSearchMode(s.getSearchMode().trim());
        if (s.getRelativity() != null) memosProperties.setRelativity(s.getRelativity());
        if (s.getIncludePreference() != null) memosProperties.setIncludePreference(s.getIncludePreference());
        if (s.getPrefTopK() != null) memosProperties.setPrefTopK(s.getPrefTopK());
        if (StringUtils.hasText(s.getDedup())) memosProperties.setDedup(s.getDedup().trim());
        if (s.getSearchToolMemory() != null) memosProperties.setSearchToolMemory(s.getSearchToolMemory());
        if (s.getIncludeSkillMemory() != null) memosProperties.setIncludeSkillMemory(s.getIncludeSkillMemory());
        if (s.getSaveAssistantTurns() != null) memosProperties.setSaveAssistantTurns(s.getSaveAssistantTurns());
        if (s.getFallbackToRag() != null) memosProperties.setFallbackToRag(s.getFallbackToRag());
        if (s.getModelInheritConnection() != null) {
            memosProperties.setModelInheritConnection(s.getModelInheritConnection());
        }
        if (s.getModelBaseUrl() != null) memosProperties.setModelBaseUrl(s.getModelBaseUrl().trim());
        if (s.getModelApiKey() != null) memosProperties.setModelApiKey(s.getModelApiKey().trim());
        if (s.getModelName() != null) memosProperties.setModelName(s.getModelName().trim());
        if (s.getEmbeddingInheritConnection() != null) {
            memosProperties.setEmbeddingInheritConnection(s.getEmbeddingInheritConnection());
        }
        if (s.getEmbeddingBaseUrl() != null) memosProperties.setEmbeddingBaseUrl(s.getEmbeddingBaseUrl().trim());
        if (s.getEmbeddingApiKey() != null) memosProperties.setEmbeddingApiKey(s.getEmbeddingApiKey().trim());
        if (s.getEmbeddingModelName() != null) {
            memosProperties.setEmbeddingModelName(s.getEmbeddingModelName().trim());
        }
        if (s.getEmbeddingDimension() != null) memosProperties.setEmbeddingDimension(s.getEmbeddingDimension());
    }

    private void applySearch(RuntimeConfig.SearchSection s) {
        if (StringUtils.hasText(s.getSearxngUrl())) searchProperties.setSearxngUrl(s.getSearxngUrl().trim());
        if (s.getQueryPlannerEnabled() != null) searchProperties.setQueryPlannerEnabled(s.getQueryPlannerEnabled());
        if (s.getPlannerTimeoutMs() != null) searchProperties.setPlannerTimeoutMs(s.getPlannerTimeoutMs());
        if (s.getMaxQueries() != null) searchProperties.setMaxQueries(s.getMaxQueries());
        if (s.getResultsPerQuery() != null) searchProperties.setResultsPerQuery(s.getResultsPerQuery());
        if (s.getFetchPages() != null) searchProperties.setFetchPages(s.getFetchPages());
        if (s.getMaxSources() != null) searchProperties.setMaxSources(s.getMaxSources());
        if (s.getPageTimeoutMs() != null) searchProperties.setPageTimeoutMs(s.getPageTimeoutMs());
        if (s.getTotalTimeoutMs() != null) searchProperties.setTotalTimeoutMs(s.getTotalTimeoutMs());
        if (s.getResultCacheMinutes() != null) searchProperties.setResultCacheMinutes(s.getResultCacheMinutes());
        if (s.getPageCacheHours() != null) searchProperties.setPageCacheHours(s.getPageCacheHours());
        if (s.getEngines() != null) searchProperties.setEngines(s.getEngines().trim());
    }

    private RuntimeConfig merge(RuntimeConfig base, RuntimeConfig patch) {
        RuntimeConfig out = deepCopy(base);
        if (patch == null) return out;

        if (patch.getLlm() != null) mergeLlm(out.getLlm(), patch.getLlm());
        if (patch.getEmbedding() != null) mergeEmbedding(out.getEmbedding(), patch.getEmbedding());
        if (patch.getVoice() != null) mergeVoice(out.getVoice(), patch.getVoice());
        if (patch.getMemos() != null) mergeMemos(out.getMemos(), patch.getMemos());
        if (patch.getSearch() != null) mergeSearch(out.getSearch(), patch.getSearch());
        if (patch.getClient() != null) mergeClient(out.getClient(), patch.getClient());
        return out;
    }

    private RuntimeConfig deepCopy(RuntimeConfig src) {
        RuntimeConfig copy = new RuntimeConfig();
        copy.setLlm(copySection(src.getLlm(), RuntimeConfig.LlmSection.class));
        copy.setEmbedding(copySection(src.getEmbedding(), RuntimeConfig.EmbeddingSection.class));
        copy.setVoice(copySection(src.getVoice(), RuntimeConfig.VoiceSection.class));
        copy.setMemos(copySection(src.getMemos(), RuntimeConfig.MemosSection.class));
        copy.setSearch(copySection(src.getSearch(), RuntimeConfig.SearchSection.class));
        copy.setClient(copySection(src.getClient(), RuntimeConfig.ClientSection.class));
        return copy;
    }

    private <T> T copySection(T src, Class<T> type) {
        if (src == null) {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.convertValue(mapper.convertValue(src, java.util.Map.class), type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void mergeLlm(RuntimeConfig.LlmSection base, RuntimeConfig.LlmSection patch) {
        if (patch.getBaseUrl() != null) base.setBaseUrl(patch.getBaseUrl());
        if (patch.getModelName() != null) base.setModelName(patch.getModelName());
        if (patch.getApiKey() != null) base.setApiKey(patch.getApiKey());
        if (patch.getThinkingMode() != null) base.setThinkingMode(patch.getThinkingMode());
        if (patch.getReasoningEffort() != null) base.setReasoningEffort(patch.getReasoningEffort());
        if (patch.getUtilityInheritConnection() != null) {
            base.setUtilityInheritConnection(patch.getUtilityInheritConnection());
        }
        if (patch.getUtilityBaseUrl() != null) base.setUtilityBaseUrl(patch.getUtilityBaseUrl());
        if (patch.getUtilityApiKey() != null) base.setUtilityApiKey(patch.getUtilityApiKey());
        if (patch.getUtilityModelName() != null) base.setUtilityModelName(patch.getUtilityModelName());
        if (patch.getUtilityThinkingMode() != null) base.setUtilityThinkingMode(patch.getUtilityThinkingMode());
        if (patch.getUtilityReasoningEffort() != null) {
            base.setUtilityReasoningEffort(patch.getUtilityReasoningEffort());
        }
        if (patch.getStreamingModelName() != null) base.setStreamingModelName(patch.getStreamingModelName());
        if (patch.getConnectTimeoutMs() != null) base.setConnectTimeoutMs(patch.getConnectTimeoutMs());
        if (patch.getReadTimeoutMs() != null) base.setReadTimeoutMs(patch.getReadTimeoutMs());
        if (patch.getMaxRetries() != null) base.setMaxRetries(patch.getMaxRetries());
    }

    private void mergeEmbedding(RuntimeConfig.EmbeddingSection base, RuntimeConfig.EmbeddingSection patch) {
        if (patch.getBaseUrl() != null) base.setBaseUrl(patch.getBaseUrl());
        if (patch.getModelName() != null) base.setModelName(patch.getModelName());
        if (patch.getApiKey() != null) base.setApiKey(patch.getApiKey());
    }

    private void mergeVoice(RuntimeConfig.VoiceSection base, RuntimeConfig.VoiceSection patch) {
        if (patch.getAsrUrl() != null) base.setAsrUrl(patch.getAsrUrl());
        if (patch.getAsrLanguage() != null) base.setAsrLanguage(patch.getAsrLanguage());
        if (patch.getAsrTimeoutMs() != null) base.setAsrTimeoutMs(patch.getAsrTimeoutMs());
        if (patch.getTtsEngine() != null) base.setTtsEngine(patch.getTtsEngine());
        if (patch.getAstraTtsBaseUrl() != null) base.setAstraTtsBaseUrl(patch.getAstraTtsBaseUrl());
        if (patch.getAstraDefaultAvatarId() != null) base.setAstraDefaultAvatarId(patch.getAstraDefaultAvatarId());
        if (patch.getAstraStreamingChunkSize() != null) {
            base.setAstraStreamingChunkSize(patch.getAstraStreamingChunkSize());
        }
        if (patch.getTtsTimeoutMs() != null) base.setTtsTimeoutMs(patch.getTtsTimeoutMs());
        if (patch.getTtsDefaultProfile() != null) base.setTtsDefaultProfile(patch.getTtsDefaultProfile());
    }

    private void mergeMemos(RuntimeConfig.MemosSection base, RuntimeConfig.MemosSection patch) {
        if (patch.getEnabled() != null) base.setEnabled(patch.getEnabled());
        if (patch.getBaseUrl() != null) base.setBaseUrl(patch.getBaseUrl());
        if (patch.getSearchTopK() != null) base.setSearchTopK(patch.getSearchTopK());
        if (patch.getSearchMode() != null) base.setSearchMode(patch.getSearchMode());
        if (patch.getRelativity() != null) base.setRelativity(patch.getRelativity());
        if (patch.getIncludePreference() != null) base.setIncludePreference(patch.getIncludePreference());
        if (patch.getPrefTopK() != null) base.setPrefTopK(patch.getPrefTopK());
        if (patch.getDedup() != null) base.setDedup(patch.getDedup());
        if (patch.getSearchToolMemory() != null) base.setSearchToolMemory(patch.getSearchToolMemory());
        if (patch.getIncludeSkillMemory() != null) base.setIncludeSkillMemory(patch.getIncludeSkillMemory());
        if (patch.getSaveAssistantTurns() != null) base.setSaveAssistantTurns(patch.getSaveAssistantTurns());
        if (patch.getFallbackToRag() != null) base.setFallbackToRag(patch.getFallbackToRag());
        if (patch.getModelInheritConnection() != null) {
            base.setModelInheritConnection(patch.getModelInheritConnection());
        }
        if (patch.getModelBaseUrl() != null) base.setModelBaseUrl(patch.getModelBaseUrl());
        if (patch.getModelApiKey() != null) base.setModelApiKey(patch.getModelApiKey());
        if (patch.getModelName() != null) base.setModelName(patch.getModelName());
        if (patch.getEmbeddingInheritConnection() != null) {
            base.setEmbeddingInheritConnection(patch.getEmbeddingInheritConnection());
        }
        if (patch.getEmbeddingBaseUrl() != null) base.setEmbeddingBaseUrl(patch.getEmbeddingBaseUrl());
        if (patch.getEmbeddingApiKey() != null) base.setEmbeddingApiKey(patch.getEmbeddingApiKey());
        if (patch.getEmbeddingModelName() != null) base.setEmbeddingModelName(patch.getEmbeddingModelName());
        if (patch.getEmbeddingDimension() != null) base.setEmbeddingDimension(patch.getEmbeddingDimension());
    }

    private void mergeSearch(RuntimeConfig.SearchSection base, RuntimeConfig.SearchSection patch) {
        if (patch.getSearxngUrl() != null) base.setSearxngUrl(patch.getSearxngUrl());
        if (patch.getQueryPlannerEnabled() != null) base.setQueryPlannerEnabled(patch.getQueryPlannerEnabled());
        if (patch.getPlannerTimeoutMs() != null) base.setPlannerTimeoutMs(patch.getPlannerTimeoutMs());
        if (patch.getMaxQueries() != null) base.setMaxQueries(patch.getMaxQueries());
        if (patch.getResultsPerQuery() != null) base.setResultsPerQuery(patch.getResultsPerQuery());
        if (patch.getFetchPages() != null) base.setFetchPages(patch.getFetchPages());
        if (patch.getMaxSources() != null) base.setMaxSources(patch.getMaxSources());
        if (patch.getPageTimeoutMs() != null) base.setPageTimeoutMs(patch.getPageTimeoutMs());
        if (patch.getTotalTimeoutMs() != null) base.setTotalTimeoutMs(patch.getTotalTimeoutMs());
        if (patch.getResultCacheMinutes() != null) base.setResultCacheMinutes(patch.getResultCacheMinutes());
        if (patch.getPageCacheHours() != null) base.setPageCacheHours(patch.getPageCacheHours());
        if (patch.getEngines() != null) base.setEngines(patch.getEngines());
    }

    private void mergeClient(RuntimeConfig.ClientSection base, RuntimeConfig.ClientSection patch) {
        if (patch.getTtsSpeed() != null) base.setTtsSpeed(patch.getTtsSpeed());
        if (patch.getTtsPitch() != null) base.setTtsPitch(patch.getTtsPitch());
        if (patch.getAutoPlayTts() != null) base.setAutoPlayTts(patch.getAutoPlayTts());
        if (patch.getDarkMode() != null) base.setDarkMode(patch.getDarkMode());
        if (patch.getProactiveChatEnabled() != null) base.setProactiveChatEnabled(patch.getProactiveChatEnabled());
        if (patch.getProactiveIdleSeconds() != null) base.setProactiveIdleSeconds(patch.getProactiveIdleSeconds());
        if (patch.getProactivePrompt() != null) base.setProactivePrompt(patch.getProactivePrompt());
        if (patch.getAutoResearchEnabled() != null) base.setAutoResearchEnabled(patch.getAutoResearchEnabled());
        if (patch.getResearchIntervalMinutes() != null) base.setResearchIntervalMinutes(patch.getResearchIntervalMinutes());
        if (patch.getResearchDeliveryIdleSeconds() != null) base.setResearchDeliveryIdleSeconds(patch.getResearchDeliveryIdleSeconds());
        if (patch.getResearchCooldownMinutes() != null) base.setResearchCooldownMinutes(patch.getResearchCooldownMinutes());
        if (patch.getResearchQuietStart() != null) base.setResearchQuietStart(patch.getResearchQuietStart());
        if (patch.getResearchQuietEnd() != null) base.setResearchQuietEnd(patch.getResearchQuietEnd());
        if (patch.getResearchScoreThreshold() != null) base.setResearchScoreThreshold(patch.getResearchScoreThreshold());
        if (patch.getRecentLlmModels() != null) base.setRecentLlmModels(patch.getRecentLlmModels());
    }
}
