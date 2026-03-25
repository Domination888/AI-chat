package org.example.aichat.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 智谱 MCP Web Search 配置。
 * 通过 SSE 方式连接智谱 MCP 搜索服务，为 LLM 提供联网搜索能力。
 */
@Slf4j
@Configuration
public class McpConfig {

    @Value("${mcp.zhipu.search-url}")
    private String searchUrl;

    @Value("${mcp.zhipu.api-key}")
    private String apiKey;

    // Spring Boot 自动将逗号分隔的字符串拆分为数组
    // 例如: java,-jar,C:/path/to/prime-mcp-server-1.0.0.jar
    @Value("${mcp.local.command:java,prime_mcp_server.jar}")
    private String[] localCommand;

    private McpClient zhipuClient;
    private McpClient localClient;

    @Bean
    public McpClient zhipuMcpClient() {
        String sseUrl = searchUrl + "?Authorization=" + apiKey;
        log.info("初始化智谱 MCP SSE 连接: {}", searchUrl);
        HttpMcpTransport transport = new HttpMcpTransport.Builder()
                .sseUrl(sseUrl)
                .timeout(Duration.ofSeconds(60))
                .logRequests(false)
                .logResponses(false)
                .build();
        zhipuClient = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
        log.info("智谱 MCP Client 初始化完成");
        return zhipuClient;
    }

    /**
     * 本地质数判断 MCP 服务（stdio 方式，通过子进程通信）。
     * 需要安装 Python 和 mcp 包：pip install mcp
     * 通过 mcp.local.enabled=true 启用。
     */
    @Bean
    @ConditionalOnProperty(name = "mcp.local.enabled", havingValue = "true")
    public McpClient localMcpClient() {
        List<String> cmd = Arrays.asList(localCommand);
        log.info("初始化本地质数 MCP Server，命令: {}", cmd);
        StdioMcpTransport transport = new StdioMcpTransport.Builder()
                .command(cmd)
                .logEvents(true)
                .build();
        localClient = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
        log.info("本地 MCP Client 初始化完成");
        return localClient;
    }

    @PreDestroy
    public void destroy() {
        for (McpClient client : new McpClient[]{zhipuClient, localClient}) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.warn("关闭 MCP Client 时出错: {}", e.getMessage());
                }
            }
        }
        log.info("所有 MCP Client 已关闭");
    }
}
