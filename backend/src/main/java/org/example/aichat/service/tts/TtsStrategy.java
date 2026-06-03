package org.example.aichat.service.tts;

import java.io.InputStream;
import java.util.function.Consumer;

/**
 * TTS 引擎策略接口，不同引擎实现各自的推理逻辑。
 * 当前唯一实现：AstraTtsStrategy（Genie-TTS，Win :5000）。
 */
public interface TtsStrategy {

    /** 引擎标识，对应 voice.tts-engine 配置 */
    String engineName();

    /** 该引擎输出的采样率（前端动态适配用） */
    int outputSampleRate();

    /** 该引擎输出是否为 raw PCM（true）还是完整 WAV（false） */
    boolean isRawPcmOutput();

    /** raw PCM 格式名（如 pcm_s16le / pcm_f32le），非 raw PCM 时返回 "wav" */
    default String pcmFormat() { return "pcm_s16le"; }

    /** 一次性 TTS：返回完整音频字节流 */
    InputStream tts(String text, String voiceId);

    /** 流式 TTS：边收边推 */
    long ttsStream(String text, String voiceId, Double speedFactor, Double pitchFactor, Consumer<byte[]> chunkConsumer);
}