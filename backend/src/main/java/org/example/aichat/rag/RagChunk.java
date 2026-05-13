package org.example.aichat.rag;

import java.util.Set;

/**
 * RAG 文档分块 + 向量表示。
 */
public record RagChunk(String source,
                       int chunkIndex,
                       String text,
                       Set<String> terms,
                       float[] embedding) {
}
