package org.example.aichat.search;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class SearchResponse {
    private String status;
    @Builder.Default
    private List<String> plannedQueries = List.of();
    @Builder.Default
    private List<SearchSource> sources = List.of();
    private String contextText;
    @Builder.Default
    private Map<String, Object> diagnostics = Map.of();

    public boolean hasSources() {
        return sources != null && !sources.isEmpty();
    }
}
