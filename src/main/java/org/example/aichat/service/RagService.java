package org.example.aichat.service;

public interface RagService {

    /**
     * 基于用户问题检索知识库，返回可直接注入 prompt 的上下文。
     */
    String retrieveContext(String query, int topK);

    /**
     * 重新加载知识库文件并重建分块索引。
     */
    int reload();
}
