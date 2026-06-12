package org.example.aichat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.aichat.dto.History;

import java.util.List;

@Mapper
public interface HistoryMapper {

    void insert(History history);

    List<History> findByConversationId(String conversationId);

    /**
     * 查询最近 limit 条消息（按 id 正序返回，方便直接用做上下文）
     */
    List<History> findRecentByConversationId(@Param("conversationId") String conversationId,
                                             @Param("limit") int limit);

    void deleteByConversationId(@Param("conversationId") String conversationId);

    void deleteById(@Param("id") String id);
}