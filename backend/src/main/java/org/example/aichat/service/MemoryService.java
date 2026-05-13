package org.example.aichat.service;

import org.apache.ibatis.annotations.Param;
import org.example.aichat.dto.Memory;

public interface MemoryService {

    Memory findByConversationId(@Param("conversationId") String conversationId);

    void updateMemory(String conversationId, String summary, int tokenCount);

    void compressIfNeeded(String conversationId);
    
    /**
     * 为角色扮演系统特制的压缩归纳，将该总结推入 RAG 的角色长期记忆
     */
    void compressAndExtractLongTermMemory(String conversationId, Integer userId, Integer roleId);
}