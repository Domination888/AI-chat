package org.example.aichat.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface VoiceService {
    
    /**
     * 调用 SenseVoice API 把音频转文字
     */
    String asr(MultipartFile audioFile);

    /**
     * 调用 GPT-SoVITS API 把文字转音频流
     */
    InputStream tts(String text, String voiceId);
}
