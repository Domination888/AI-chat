package org.example.aichat.service;

import org.apache.ibatis.annotations.Param;
import org.example.aichat.dto.Memory;

public interface MemoryService {

    Memory findByConversationId(@Param("conversationId") String conversationId);

    void updateMemory(String conversationId, String summary, int tokenCount);

    void compressIfNeeded(String conversationId);
}