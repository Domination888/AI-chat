package org.example.aichat.search;

import java.util.List;

public record SearchProgress(String stage, String message, List<SearchSource> sources) {
    public static SearchProgress of(String stage, String message) {
        return new SearchProgress(stage, message, List.of());
    }
}
