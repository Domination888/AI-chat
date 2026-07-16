package org.example.aichat.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchHelperTest {

    @Test
    void shouldPreSearchForFreshAndExternalFacts() {
        assertTrue(WebSearchHelper.shouldPreSearch("OpenAI 最新模型是什么"));
        assertTrue(WebSearchHelper.shouldPreSearch("今天英伟达股价多少"));
        assertTrue(WebSearchHelper.shouldPreSearch("Spring Boot 3.5.11 更新了什么"));
        assertTrue(WebSearchHelper.shouldPreSearch("帮我查一下 Docker Desktop 下载地址"));
    }

    @Test
    void shouldStillSkipVagueWeatherFollowUp() {
        assertFalse(WebSearchHelper.shouldPreSearch("具体天气情况如何"));
    }

    @Test
    void optimizeQueryAddsFreshnessForWebIntent() {
        String query = WebSearchHelper.optimizeQuery("帮我查一下 Docker Desktop 下载地址");

        assertTrue(query.contains("Docker Desktop 下载地址"));
        assertTrue(query.contains("最新"));
    }
}
