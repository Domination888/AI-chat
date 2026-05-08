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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MCP 客户端配置：智谱 SSE + 本地 stdio prime-mcp-server。
 */
@Slf4j
@Configuration
public class McpConfig {

    @Value("${mcp.zhipu.search-url}")
    private String searchUrl;

    @Value("${mcp.zhipu.api-key}")
    private String apiKey;

    /**
     * prime-mcp-server fat jar 的路径。优先级：
     *   1. 环境变量 PRIME_MCP_JAR（在 yml 里通过 ${PRIME_MCP_JAR:...} 引入）
     *   2. yml mcp.local.jar-path
     *   3. 默认 prime-mcp-server/target/prime-mcp-server-1.0.0.jar（相对工作目录）
     * 相对路径会基于 user.dir（后端启动时所在目录）解析为绝对路径。
     */
    @Value("${mcp.local.jar-path:prime-mcp-server/target/prime-mcp-server-1.0.0.jar}")
    private String localJarPath;

    /** java 命令前缀，逗号分隔。默认 java,-jar；如需调参可改为 java,-Xmx256m,-jar 等。 */
    @Value("${mcp.local.java-args:java,-jar}")
    private String[] localJavaArgs;

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
     * 本地 prime-mcp-server（stdio 子进程通信）。
     * 通过 mcp.local.enabled=true 启用；jar 不存在时降级为不创建 Bean，启动不阻塞。
     */
    @Bean
    @ConditionalOnProperty(name = "mcp.local.enabled", havingValue = "true")
    public McpClient localMcpClient() {
        Path jar = resolveJarPath(localJarPath);
        if (!Files.isRegularFile(jar)) {
            log.warn("prime-mcp jar 不存在: {} （工作目录={}），跳过本地 MCP 客户端初始化。"
                    + "可执行 cd prime-mcp-server && ../mvnw package 后重启。",
                    jar, System.getProperty("user.dir"));
            return null;
        }

        List<String> cmd = new ArrayList<>(Arrays.asList(localJavaArgs));
        cmd.add(jar.toString());
        log.info("初始化本地 prime-mcp-server，命令: {}", cmd);

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

    private Path resolveJarPath(String raw) {
        Path p = Paths.get(raw);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir")).resolve(p);
        }
        return p.normalize();
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
