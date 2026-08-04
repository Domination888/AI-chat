package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import org.example.aichat.search.SearchHealth;
import org.example.aichat.search.SearchRequest;
import org.example.aichat.search.SearchResponse;
import org.example.aichat.search.WebSearchGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final WebSearchGateway webSearchGateway;

    @GetMapping("/health")
    public SearchHealth health() {
        return webSearchGateway.health();
    }

    @PostMapping("/test")
    public ResponseEntity<SearchResponse> test(@RequestBody Map<String, Object> body) {
        String query = body.get("query") == null ? "" : body.get("query").toString().strip();
        if (query.isBlank()) return ResponseEntity.badRequest().build();
        List<String> context = body.get("conversationContext") instanceof List<?> values
                ? values.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
        SearchResponse response = webSearchGateway.search(SearchRequest.builder()
                .query(query)
                .conversationContext(context)
                .language(text(body.get("language")))
                .timeRange(text(body.get("timeRange")))
                .maxSources(integer(body.get("maxSources"), 3))
                .build());
        return ResponseEntity.ok(response);
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().strip();
    }

    private static int integer(Object value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (Exception ignored) { return fallback; }
    }
}
