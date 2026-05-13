package org.example.aichat.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.function.Consumer;

public interface VoiceService {

    /** 调用 SenseVoice API 把音频转文字（默认 language=auto，无热词） */
    String asr(MultipartFile audioFile);

    /** 带语言/热词参数的 ASR 调用 */
    String asr(MultipartFile audioFile, String hotwords, String language);

    /**
     * 一次性 TTS：返回完整 wav 字节流。
     * 内部仍使用 GPT-SoVITS streaming_mode 协议（边产生边收），但合并成一个 wav 后再返回。
     * 用途：兼容旧的 /api/audio/chat 阻塞接口。
     */
    InputStream tts(String text, String voiceId);

    /**
     * 真·流式 TTS：边收 GPT-SoVITS 输出的 wav 字节，边通过 chunkConsumer 推给上游。
     * chunkConsumer 接收的每一块都是 raw bytes（拼接后是一个完整的 wav 文件）。
     *
     * @return 实际写出的总字节数；<=0 表示失败
     */
    long ttsStream(String text, String voiceId, Consumer<byte[]> chunkConsumer);
    
    /**
     * 支持动态TTS参数的流式TTS
     */
    long ttsStreamWithParams(String text, String voiceId, Double speedFactor, Consumer<byte[]> chunkConsumer);
    
    /**
     * 支持完整TTS参数的流式TTS（包括音调）
     */
    long ttsStreamWithFullParams(String text, String voiceId, Double speedFactor, Double pitchFactor, Consumer<byte[]> chunkConsumer);
}