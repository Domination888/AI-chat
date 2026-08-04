package org.example.aichat.search;

import java.util.Map;

public record SearchHealth(boolean available, String searxngUrl, Map<String, Long> engineSuccesses,
                           Map<String, Long> engineFailures, String lastError) {
}
