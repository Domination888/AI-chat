package org.example.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * voice.* 配置统一映射。
 *
 * 强约束（与 .joycode/rules/00-hardware-and-deployment.md 对齐）：
 *   - ASR / TTS 必须在 Mac 本机
 *   - GPT-SoVITS 走 api_v2，端口 9880
 *   - 模型权重 / 参考音频走绝对路径
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "voice")
public class VoiceProperties {

    // -------- ASR --------
    private String asrUrl;
    private String asrLanguage = "auto";
    private int asrTimeoutMs = 15000;

    // -------- TTS --------
    private String ttsBaseUrl = "http://127.0.0.1:9880";
    private boolean ttsAutoSwitchWeights = true;
    private int ttsStreamingMode = 2;
    private int ttsTimeoutMs = 60000;
    private String ttsDefaultProfile = "default";

    /** voiceId -> profile 配置 */
    private Map<String, Profile> ttsProfiles = new LinkedHashMap<>();

    @Data
    public static class Profile {
        /** 仅用于日志 / 调试 */
        private String displayName;

        /** 直接复用其它 profile 的全部参数（避免重复配置） */
        private String aliasOf;

        // GPT-SoVITS /tts 入参
        private String refAudioPath;
        private String promptText = "";
        private String promptLang = "zh";
        private String textLang = "zh";

        // 权重切换
        private String gptWeights;
        private String sovitsWeights;

        // 推理参数（必须对齐 GPT-SoVITS webui "1C-推理" 页设置，否则音色漂 + 慢）
        private int topK = 15;
        private double topP = 1.0;
        private double temperature = 1.0;
        private double speedFactor = 1.0;
        /** 句间停顿秒数，webui 默认 0.3 */
        private double fragmentInterval = 0.3;
        /**
         * 采样步数（v2Pro/v3/v4 的 VITS 扩散步数）。
         * api_v2 默认 32，webui 默认 8 —— 漏传会让推理慢 4 倍且采样不稳。
         */
        private int sampleSteps = 8;
        /**
         * 文本切分方式，对齐 webui 选项：
         * cut0=不切, cut1=凑四句一切, cut2=凑50字一切, cut3=按中文句号切, cut4=按英文句号切, cut5=按标点切
         * webui 黍模型默认是"凑四句一切" → cut1
         */
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