package org.example.aichat.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.aichat.config.LlmModelFactory;
import org.example.aichat.config.LlmProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebResearchServiceTest {

    @Test
    void extractsArticleAndDropsNavigationNoise() {
        Document document = Jsoup.parse("""
                <html><head><title>测试文章</title>
                <meta property="article:published_time" content="2026-07-22" /></head>
                <body><nav>这是导航区域，不应进入正文内容</nav><main><article>
                <h1>搜索质量改进</h1>
                <p>这是第一段足够长的正文内容，用来验证页面正文提取逻辑能够保留有意义的信息。</p>
                <p>这是第二段足够长的正文内容，同时应该从结果中移除脚本、导航和广告等噪声。</p>
                </article></main><script>window.secret='noise'</script></body></html>
                """);

        WebResearchService.PageContent page = WebResearchService.extractPage(document, "https://example.com/post");

        assertEquals("测试文章", page.title());
        assertEquals("2026-07-22", page.publishedAt());
        assertTrue(page.text().contains("第一段"));
        assertTrue(page.text().contains("第二段"));
        assertFalse(page.text().contains("导航区域"));
        assertFalse(page.text().contains("window.secret"));
    }

    @Test
    void rejectsPrivateAndSpecialAddresses() throws Exception {
        assertFalse(WebResearchService.isPublicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(WebResearchService.isPublicAddress(InetAddress.getByName("10.0.0.1")));
        assertFalse(WebResearchService.isPublicAddress(InetAddress.getByName("169.254.169.254")));
        assertFalse(WebResearchService.isPublicAddress(InetAddress.getByName("100.64.0.1")));
        assertFalse(WebResearchService.isPublicAddress(InetAddress.getByName("::1")));
        assertTrue(WebResearchService.isPublicAddress(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void deterministicPlanningDoesNotAddLatestToOrdinaryQueries() {
        WebResearchService service = serviceWithDeterministicPlanner();

        WebResearchService.QueryPlan plan = service.planQueries(
                "Spring Boot 依赖注入原理", List.of(), null, null);

        assertEquals("", plan.timeRange());
        assertTrue(plan.queries().stream().noneMatch(q -> q.contains("最新")));
    }

    @Test
    void followUpPlanningUsesRecentContextAndPrioritizesOfficialDomain() {
        WebResearchService service = serviceWithDeterministicPlanner();

        WebResearchService.QueryPlan plan = service.planQueries(
                "它的官方文档呢", List.of("Spring Boot 3.5 有哪些变化"), "zh-CN", null);

        assertTrue(plan.queries().get(0).contains("site:docs.spring.io"));
        assertTrue(plan.queries().get(0).contains("Spring Boot"));
        assertTrue(plan.queries().size() <= 3);
    }

    private static WebResearchService serviceWithDeterministicPlanner() {
        SearchProperties properties = new SearchProperties();
        properties.setQueryPlannerEnabled(false);
        return new WebResearchService(properties, null,
                new LlmModelFactory(new LlmProperties()), new ObjectMapper());
    }
}
