package org.example.aichat.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.aichat.dto.History;
import org.example.aichat.mapper.HistoryMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class HistoryService {

    private final HistoryMapper historyMapper;

    public void save(String conversationId, String sender, String content) {

        History history = new History();
        history.setConversationId(conversationId);
        history.setSender(sender);
        history.setContent(content);
        history.setTokenCount(content.length() / 4);

        historyMapper.insert(history);
    }

    public List<History> getHistory(String conversationId) {
        return historyMapper.findByConversationId(conversationId);
    }
}