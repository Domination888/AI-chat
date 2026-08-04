package org.example.aichat.search;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.function.Consumer;

@Data
@Builder
public class SearchRequest {
    private String query;
    @Builder.Default
    private List<String> conversationContext = List.of();
    private String language;
    private String timeRange;
    private Integer maxSources;
    private Consumer<SearchProgress> progressListener;
}
