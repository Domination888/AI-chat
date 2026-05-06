package org.example.aichat.controller;

import org.example.aichat.dto.ChatRequest;
import org.example.aichat.service.ChatService;
import org.example.aichat.service.VoiceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.InputStream;

@RestController
@RequestMapping("/api/audio")
public class AudioController {

    @Autowired
    private VoiceService voiceService;
    
    @Autowired
    private ChatService chatService;

    /**
     * 接收前端语音 -> ASR转文字 -> 存入聊天逻辑(并调用大模型) -> 返回大模型的文本回答
     * 注意：此接口可以仅返回文本，前端再基于文本通过 Websocket 或直接轮询调 TTS。
     * 或者在这里直接阻塞等待大模型回答完再流式输出音频。
     */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> audioChat(
            @RequestParam("file") MultipartFile audioFile,
            @RequestParam("conversationId") String conversationId,
            @RequestParam("roleId") Integer roleId,
            @RequestParam("voiceId") String voiceId,
            @RequestParam("userId") Integer userId) {
            
        // 1. 语音转文字 (ASR)
        String userText = voiceService.asr(audioFile);
        if (userText == null || userText.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("无法识别音频内容");
        }

        // 2. 调用大模型进行角色扮演回复 (Phase 4我们将重构的 ChatService.chat)
        ChatRequest req = new ChatRequest();
        req.setConversationId(conversationId);
        req.setMessage(userText);
        req.setUserId(String.valueOf(userId));
        
        // 阻塞式调用一次性拿到完整结果，以便直接转TTS。如果是SSE还需要更复杂的处理。
        String aiResponseText = chatService.chatBlocking(conversationId, userText, userId, roleId);
        
        // 3. 将 AI 的回复转为语音流 (TTS)
        InputStream audioStream = voiceService.tts(aiResponseText, voiceId);
        
        if (audioStream == null) {
            return ResponseEntity.internalServerError().body("TTS 转换失败");
        }

        HttpHeaders headers = new HttpHeaders();
        // 返回 wav 流，具体格式根据 GPT-SoVITS 实际输出而定
        headers.setContentType(MediaType.valueOf("audio/wav"));
        // 也可以通过自定义 header 将大模型的文字包进去，让前端既能播放声音也能展示文字
        headers.add("X-AI-Response-Text", java.net.URLEncoder.encode(aiResponseText, java.nio.charset.StandardCharsets.UTF_8));

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(audioStream));
    }
}
