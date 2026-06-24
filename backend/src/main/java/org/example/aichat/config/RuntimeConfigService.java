package org.example.aichat.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.service.impl.VoiceServiceImpl;
import org.example.aichat.service.memos.MemosClient;
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
        llm.setStreamingModelName(llmProperties.getStreamingModelName());
        llm.setConnectTimeoutMs(llmProperties.getConnectTimeoutMs());
        llm.setReadTimeoutMs(llmProperties.getReadTimeoutMs());
        llm.setMaxRetries(llmProperties.getMaxRetries());
        cfg.setLlm(llm);

        RuntimeConfig.EmbeddingSection emb = new RuntimeConfig.EmbeddingSection();
        emb.setBaseUrl(embeddingProperties.getBaseUrl());
        emb.setModelName(embeddingProperties.getModelName());
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
        memos.setFallbackToRag(memosProperties.isFallbackToRag());
        cfg.setMemos(memos);

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
    }

    private void applyVoice(RuntimeConfig.VoiceSection s) {
        if (StringUtils.hasText(s.getAsrUrl())) voiceProperties.setAsrUrl(s.getAsrUrl().trim());
        if (StringUtils.hasText(s.getAsrLanguage())) voiceProperties.setAsrLanguage(s.getAsrLanguage().trim());
        if (s.getAsrTimeoutMs() != null) voiceProperties.setAsrTimeoutMs(s.getAsrTimeoutMs());
        if (StringUtils.hasText(s.getTtsEngine())) voiceProperties.setTtsEngine(s.getTtsEngine().trim());
        if (StringUtils.hasText(s.getAstraTtsBaseUrl())) {
            voiceProperties.setAstraTtsBaseUrl(s.getAstraTtsBaseUrl().trim());
        }
        if (StringUtils.hasText(s.getAstraDefaultAvatarId())) {
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
        if (s.getFallbackToRag() != null) memosProperties.setFallbackToRag(s.getFallbackToRag());
    }

    private RuntimeConfig merge(RuntimeConfig base, RuntimeConfig patch) {
        RuntimeConfig out = deepCopy(base);
        if (patch == null) return out;

        if (patch.getLlm() != null) mergeLlm(out.getLlm(), patch.getLlm());
        if (patch.getEmbedding() != null) mergeEmbedding(out.getEmbedding(), patch.getEmbedding());
        if (patch.getVoice() != null) mergeVoice(out.getVoice(), patch.getVoice());
        if (patch.getMemos() != null) mergeMemos(out.getMemos(), patch.getMemos());
        if (patch.getClient() != null) mergeClient(out.getClient(), patch.getClient());
        return out;
    }

    private RuntimeConfig deepCopy(RuntimeConfig src) {
        RuntimeConfig copy = new RuntimeConfig();
        copy.setLlm(copySection(src.getLlm(), RuntimeConfig.LlmSection.class));
        copy.setEmbedding(copySection(src.getEmbedding(), RuntimeConfig.EmbeddingSection.class));
        copy.setVoice(copySection(src.getVoice(), RuntimeConfig.VoiceSection.class));
        copy.setMemos(copySection(src.getMemos(), RuntimeConfig.MemosSection.class));
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
        if (patch.getStreamingModelName() != null) base.setStreamingModelName(patch.getStreamingModelName());
        if (patch.getConnectTimeoutMs() != null) base.setConnectTimeoutMs(patch.getConnectTimeoutMs());
        if (patch.getReadTimeoutMs() != null) base.setReadTimeoutMs(patch.getReadTimeoutMs());
        if (patch.getMaxRetries() != null) base.setMaxRetries(patch.getMaxRetries());
    }

    private void mergeEmbedding(RuntimeConfig.EmbeddingSection base, RuntimeConfig.EmbeddingSection patch) {
        if (patch.getBaseUrl() != null) base.setBaseUrl(patch.getBaseUrl());
        if (patch.getModelName() != null) base.setModelName(patch.getModelName());
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
        if (patch.getFallbackToRag() != null) base.setFallbackToRag(patch.getFallbackToRag());
    }

    private void mergeClient(RuntimeConfig.ClientSection base, RuntimeConfig.ClientSection patch) {
        if (patch.getTtsSpeed() != null) base.setTtsSpeed(patch.getTtsSpeed());
        if (patch.getTtsPitch() != null) base.setTtsPitch(patch.getTtsPitch());
        if (patch.getAutoPlayTts() != null) base.setAutoPlayTts(patch.getAutoPlayTts());
        if (patch.getDarkMode() != null) base.setDarkMode(patch.getDarkMode());
        if (patch.getProactiveChatEnabled() != null) base.setProactiveChatEnabled(patch.getProactiveChatEnabled());
        if (patch.getProactiveIdleSeconds() != null) base.setProactiveIdleSeconds(patch.getProactiveIdleSeconds());
        if (patch.getProactivePrompt() != null) base.setProactivePrompt(patch.getProactivePrompt());
        if (patch.getRecentLlmModels() != null) base.setRecentLlmModels(patch.getRecentLlmModels());
    }
}
