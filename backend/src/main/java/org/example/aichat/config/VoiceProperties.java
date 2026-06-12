package org.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * voice.* 配置统一映射。
 *
 * TTS 引擎已切换为 Astra（Genie-TTS，部署在 Win :5000），
 * 采样率 32000Hz，通过 avatarId 选择音色。
 */
@Data
@Component
@ConfigurationProperties(prefix = "voice")
public class VoiceProperties {

    // -------- ASR --------
    private String asrUrl;
    private String asrLanguage = "auto";
    private int asrTimeoutMs = 15000;

    // -------- TTS (Astra / Genie-TTS on Win :5000) --------
    /** TTS 引擎：astra（唯一选项） */
    private String ttsEngine = "astra";
    /** Astra TTS 服务基础 URL */
    private String astraTtsBaseUrl;
    /** Astra 默认 avatarId（兜底音色） */
    private String astraDefaultAvatarId = "chenxing";
    /** Astra 流式分片大小（predict-stream chunkSize 参数） */
    private int astraStreamingChunkSize = 2048;
    private int ttsTimeoutMs = 60000;
    private String ttsDefaultProfile = "shu";

    /** voiceId -> profile 配置 */
    private Map<String, Profile> ttsProfiles = new LinkedHashMap<>();

    @Data
    public static class Profile {
        /** 仅用于日志 / 调试 */
        private String displayName;

        /** 直接复用其它 profile 的全部参数（避免重复配置） */
        private String aliasOf;

        // Astra avatarId（对应 Win 上 TTS 服务的音色 ID）
        private String astraAvatarId;

        // 推理参数（Astra predict-stream 可选参数）
        private String textLang = "zh";
        private double speedFactor = 1.0;
        private double temperature = 1.0;
        private int topK = 15;
        private double topP = 1.0;

        // 以下字段保留但不再用于 GPT-SoVITS，仅供 aliasOf 链兼容
        private String refAudioPath;
        private String promptText = "";
        private String promptLang = "zh";
        private String gptWeights;
        private String sovitsWeights;
        private double fragmentInterval = 0.3;
        private int sampleSteps = 8;
        private String textSplitMethod = "cut1";
    }

    /**
     * 解析 voiceId（来自 RoleCard.voiceId）-> 实际 Profile。
     * 找不到时回退到 ttsDefaultProfile。
     */
    public Profile resolveProfile(String voiceId) {
        String key = (voiceId == null || voiceId.isBlank()) ? ttsDefaultProfile : voiceId;
        Profile p = ttsProfiles.get(key);
        if (p == null) {
            p = ttsProfiles.get(ttsDefaultProfile);
        }
        if (p == null) {
            return null;
        }
        // 解析 alias 链（最多 5 跳防止环）
        int hop = 0;
        while (p != null && p.getAliasOf() != null && !p.getAliasOf().isBlank() && hop++ < 5) {
            Profile next = ttsProfiles.get(p.getAliasOf());
            if (next == null) break;
            p = next;
        }
        return p;
    }
}