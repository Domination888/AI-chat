package org.example.aichat.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring Bean 包装：每次调用委托给 {@link EmbeddingModelHolder} 当前实例。
 */
@Component
@RequiredArgsConstructor
public class DynamicEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModelHolder holder;

    @Override
    public Response<Embedding> embed(String text) {
        return holder.get().embed(text);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        return holder.get().embedAll(segments);
    }

    @Override
    public int dimension() {
        return holder.get().dimension();
    }
}
