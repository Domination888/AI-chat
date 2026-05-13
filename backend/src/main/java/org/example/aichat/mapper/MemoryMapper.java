package org.example.aichat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.aichat.dto.Memory;

@Mapper
public interface MemoryMapper {

    Memory findByConversationId(String conversationId);

    void insert(Memory memory);

    void update(Memory memory);

    void deleteByConversationId(String conversationId);
}