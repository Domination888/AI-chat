package org.example.aichat.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "search.research")
public class SearchProperties {
    private String searxngUrl = "http://127.0.0.1:8888";
    private boolean queryPlannerEnabled = true;
    private int plannerTimeoutMs = 5000;
    private int maxQueries = 3;
    private int resultsPerQuery = 10;
    private int fetchPages = 5;
    private int maxSources = 3;
    private int pageTimeoutMs = 8000;
    private int totalTimeoutMs = 20000;
    private int maxResponseBytes = 2 * 1024 * 1024;
    private int maxPageChars = 15000;
    private int resultCacheMinutes = 15;
    private int pageCacheHours = 6;
    private String engines = "brave,duckduckgo,bing,baidu,sogou,360search";
}
