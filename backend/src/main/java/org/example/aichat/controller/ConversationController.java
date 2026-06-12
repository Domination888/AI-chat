package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import org.example.aichat.dto.Conversation;
import org.example.aichat.dto.History;
import org.example.aichat.mapper.ConversationMapper;
import org.example.aichat.mapper.HistoryMapper;
import org.example.aichat.util.EmotionTagNormalizer;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationMapper conversationMapper;
    private final HistoryMapper historyMapper;

    @GetMapping("/user/{userId}")
    public List<Conversation> getUserConversations(@PathVariable Integer userId) {
        return conversationMapper.findByUserId(userId);
    }

    @GetMapping("/user/{userId}/role/{roleId}")
    public List<Conversation> getUserConversationsByRole(@PathVariable Integer userId, @PathVariable Integer roleId) {
        return conversationMapper.findByUserIdAndRoleId(userId, roleId);
    }

    @GetMapping("/{conversationId}/history")
    public List<History> getHistory(@PathVariable String conversationId) {
        List<History> list = historyMapper.findByConversationId(conversationId);
        // 对 assistant 消息剥离所有 <xxx> 情绪标签，避免历史回放时露出 <开心> 等标签给用户。
        // user 消息保持原样（用户输入的尖括号内容不应被吞掉）。
        if (list != null) {
            for (History h : list) {
                if ("assistant".equals(h.getSender()) && h.getContent() != null) {
                    h.setContent(EmotionTagNormalizer.stripAllTags(h.getContent()));
                }
            }
        }
        return list;
    }
    @DeleteMapping("/{conversationId}")
    public void deleteConversation(@PathVariable String conversationId) {
        historyMapper.deleteByConversationId(conversationId);
        conversationMapper.deleteById(conversationId);
    }}