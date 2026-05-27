package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.service.VoiceService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 独立音频工具接口（ASR / TTS）。
 * <p>
 * 聊天功能已统一到 ChatController（POST /api/chat），
 * 语音输入和文本输入都走同一个入口，支持流式返回、联网、多模态、tools、RAG、TTS 回播。
 * <p>
 * 本 Controller 仅保留：
 * - POST /api/audio/asr  → 纯 ASR（录音转文字）
 * - GET  /api/audio/tts  → 纯 TTS（文字转语音，单句）
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/audio")
public class AudioController {

    private final VoiceService voiceService;

    // ==========================================================
    // 1) 仅 ASR：录音转文字
    // ==========================================================
    @PostMapping(value = "/asr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> asrOnly(
            @RequestParam("file") MultipartFile audioFile,
            @RequestParam(value = "hotwords", required = false) String hotwords,
            @RequestParam(value = "language", required = false) String language) {
        Map<String, Object> resp = new HashMap<>();
        if (audioFile == null || audioFile.isEmpty()) {
            resp.put("ok", false);
            resp.put("error", "empty audio");
            return ResponseEntity.badRequest().body(resp);
        }
        String text = voiceService.asr(audioFile, hotwords, language);
        resp.put("ok", text != null && !text.trim().isEmpty());
        resp.put("text", text == null ? "" : text);
        return ResponseEntity.ok(resp);
    }

    // ==========================================================
    // 2) 单句 TTS（流式 wav）：前端按句子拉
    //    GET /api/audio/tts?text=...&voiceId=shu
    // ==========================================================
    @GetMapping(value = "/tts")
    public ResponseEntity<InputStreamResource> ttsOnly(
            @RequestParam("text") String text,
            @RequestParam(value = "voiceId", required = false) String voiceId,
            @RequestParam(value = "speedFactor", required = false) Double speedFactor) {
        InputStream in = voiceService.tts(text, voiceId);
        if (in == null) {
            return ResponseEntity.internalServerError().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("audio/wav"));
        headers.setCacheControl("no-cache");
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(in));
    }
}