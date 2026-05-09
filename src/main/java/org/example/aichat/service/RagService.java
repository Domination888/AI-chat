package org.example.aichat.service;

public interface RagService {

    /**
     * 基于用户问题检索知识库（全局），返回可直接注入 prompt 的上下文。
     */
    String retrieveContext(String query, int topK);

    /**
     * 基于用户问题检索指定角色知识库（roleCode 目录），返回可直接注入 prompt 的上下文。
     */
    String retrieveContext(String roleCode, String query, int topK);

    /**
     * 重新加载知识库文件并重建分块索引。
     */
    int reload();
    
    /**
     * 将记忆存入指定角色和用户的长期记忆流中
     */
    void addLongTermMemory(Integer userId, Integer roleId, String summaryText);

    /**
     * 针对特定角色、特定用户搜索相关记忆
     */
    String searchLongTermMemoryContext(Integer userId, Integer roleId, String query, int topK);
}
