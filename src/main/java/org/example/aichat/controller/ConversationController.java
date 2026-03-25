package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import org.example.aichat.dto.Conversation;
import org.example.aichat.dto.History;
import org.example.aichat.mapper.ConversationMapper;
import org.example.aichat.mapper.HistoryMapper;
import org.example.aichat.mapper.MemoryMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationMapper conversationMapper;
    private final HistoryMapper historyMapper;
    private final MemoryMapper memoryMapper;

    @GetMapping("/user/{userId}")
    public List<Conversation> getUserConversations(@PathVariable Integer userId) {
        return conversationMapper.findByUserId(userId);
    }

    @GetMapping("/{conversationId}/history")
    public List<History> getHistory(@PathVariable String conversationId) {
        return historyMapper.findByConversationId(conversationId);
    }
    @DeleteMapping("/{conversationId}")
    public void deleteConversation(@PathVariable String conversationId) {
        historyMapper.deleteByConversationId(conversationId);
        memoryMapper.deleteByConversationId(conversationId);
        conversationMapper.deleteById(conversationId);
    }}