package org.example.aichat.service;

import org.example.aichat.search.SearchSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProactiveResearchServiceTest {

    @Test
    void quietHoursCanCrossMidnight() {
        LocalTime start = LocalTime.of(23, 0);
        LocalTime end = LocalTime.of(9, 0);

        assertTrue(ProactiveResearchService.isWithinQuietHours(LocalTime.of(23, 30), start, end));
        assertTrue(ProactiveResearchService.isWithinQuietHours(LocalTime.of(8, 59), start, end));
        assertFalse(ProactiveResearchService.isWithinQuietHours(LocalTime.of(9, 0), start, end));
        assertFalse(ProactiveResearchService.isWithinQuietHours(LocalTime.of(15, 0), start, end));
    }

    @Test
    void equalQuietHourBoundsDisableQuietHours() {
        assertFalse(ProactiveResearchService.isWithinQuietHours(
                LocalTime.NOON, LocalTime.of(9, 0), LocalTime.of(9, 0)));
    }

    @Test
    void successfullyReadPrimarySourceCanPassReliableTopicThreshold() {
        SearchSource source = SearchSource.builder()
                .title("可靠来源")
                .url("https://example.com/article")
                .score(0.54)
                .pageRead(true)
                .excerpts(List.of("已成功读取的正文片段"))
                .build();

        assertTrue(ProactiveResearchService.calculateCandidateScore(source) >= 80.0);
    }

    @Test
    void snippetOnlyPrimarySourceDoesNotPassReliableTopicThreshold() {
        SearchSource source = SearchSource.builder()
                .title("仅搜索摘要")
                .url("https://example.com/dynamic")
                .score(0.80)
                .pageRead(false)
                .excerpts(List.of())
                .build();

        assertTrue(ProactiveResearchService.calculateCandidateScore(source) < 80.0);
    }
}
