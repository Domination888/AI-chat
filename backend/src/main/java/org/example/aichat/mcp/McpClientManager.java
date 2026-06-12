package org.example.aichat.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 客户端注册中心：根据 {@link McpServerStore} 中的配置，统一拉起/管理所有启用的 MCP 服务器，
 * 聚合它们的工具，并按工具名路由 tool-call。支持运行时热重载。
 *
 * 替代旧的 McpConfig（智谱 SSE + 单一本地 stdio）。
 */
@Slf4j
@Component
public class McpClientManager {

    private final McpServerStore store;
    private final AppPaths appPaths;

    /** serverId -> 已连接的 McpClient */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    /** 工具名 -> serverId（路由用） */
    private final Map<String, String> toolToServer = new ConcurrentHashMap<>();
    /** serverId -> 运行时状态 */
    private final Map<String, ServerStatus> statuses = new ConcurrentHashMap<>();
    /** 聚合后的全部工具规格 */
    private volatile List<ToolSpecification> allToolSpecs = new ArrayList<>();

    public McpClientManager(McpServerStore store, AppPaths appPaths) {
        this.store = store;
        this.appPaths = appPaths;
    }

    @Data
    public static class ServerStatus {
        private String id;
        private String name;
        private boolean enabled;
        private boolean connected;
        private int toolCount;
        private List<String> toolNames = new ArrayList<>();
        private String error;
    }

    @PostConstruct
    public synchronized void reload() {
        closeAll();
        toolToServer.clear();
        statuses.clear();
        List<ToolSpecification> aggregated = new ArrayList<>();

        for (McpServerConfig cfg : store.list()) {
            ServerStatus status = new ServerStatus();
            status.setId(cfg.getId());
            status.setName(cfg.getName());
            status.setEnabled(cfg.isEnabled());
            statuses.put(cfg.getId(), status);

            if (!cfg.isEnabled()) {
                continue;
            }
            try {
                McpClient client = buildClient(cfg);
                List<ToolSpecification> tools = client.listTools();
                clients.put(cfg.getId(), client);
                status.setConnected(true);
                status.setToolCount(tools.size());
                for (ToolSpecification spec : tools) {
                    String toolName = spec.name();
                    status.getToolNames().add(toolName);
                    if (toolToServer.containsKey(toolName)) {
                        log.warn("工具名冲突: '{}' 已由 {} 提供，忽略 {} 的同名工具",
                                toolName, toolToServer.get(toolName), cfg.getId());
                        continue;
                    }
                    toolToServer.put(toolName, cfg.getId());
                    aggregated.add(spec);
                }
                log.info("MCP 服务器 [{}] 已连接，工具: {}", cfg.getId(), status.getToolNames());
            } catch (Exception e) {
                status.setConnected(false);
                status.setError(e.getMessage());
                log.warn("MCP 服务器 [{}] 启动失败（降级跳过）: {}", cfg.getId(), e.getMessage());
            }
        }
        this.allToolSpecs = aggregated;
        log.info("MCP 注册中心就绪：{} 个已连接服务器，{} 个工具", clients.size(), aggregated.size());
    }

    private McpClient buildClient(McpServerConfig cfg) {
        McpTransport transport;
        if (cfg.isSse()) {
            if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
                throw new IllegalArgumentException("sse 服务器缺少 url");
            }
            HttpMcpTransport.Builder b = new HttpMcpTransport.Builder()
                    .sseUrl(cfg.getUrl())
                    .timeout(Duration.ofSeconds(60))
                    .logRequests(false)
                    .logResponses(false);
            if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
                b.customHeaders(cfg.getHeaders());
            }
            transport = b.build();
        } else {
            if (cfg.getCommand() == null || cfg.getCommand().isEmpty()) {
                throw new IllegalArgumentException("stdio 服务器缺少 command");
            }
            List<String> command = resolveCommand(cfg.getCommand());
            StdioMcpTransport.Builder b = new StdioMcpTransport.Builder()
                    .command(command)
                    .logEvents(false);
            if (cfg.getEnv() != null && !cfg.getEnv().isEmpty()) {
                b.environment(cfg.getEnv());
            }
            transport = b.build();
        }

        return new DefaultMcpClient.Builder()
                .transport(transport)
                .clientName("ai-chat")
                .key(cfg.getId())
                .initializationTimeout(Duration.ofSeconds(20))
                .toolExecutionTimeout(Duration.ofSeconds(60))
                .build();
    }

    /** 把命令里形如 *.jar 的相对路径解析为基于项目根的绝对路径。 */
    private List<String> resolveCommand(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String token : raw) {
            if (token != null && token.endsWith(".jar")) {
                out.add(appPaths.resolveAgainstRoot(token).toString());
            } else {
                out.add(token);
            }
        }
        return out;
    }

    /** 全部已连接服务器聚合的工具规格（供 LLM 选择调用）。 */
    public List<ToolSpecification> listAllTools() {
        return allToolSpecs;
    }

    /**
     * 组装【MCP 工具目录】提示片段，让模型在 system prompt 中也能看到已注册工具（不仅依赖 API 侧 toolSpecifications）。
     */
    public String buildToolsCatalogSection() {
        List<ToolSpecification> tools = allToolSpecs;
        if (tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【MCP 工具目录】\n");
        sb.append("下列工具已通过 MCP 注册，可直接调用；无对应技能说明时，仍应主动选用合适工具，不要心算或编造结果。\n");
        for (ToolSpecification spec : tools) {
            sb.append("- `").append(spec.name()).append("`");
            if (spec.description() != null && !spec.description().isBlank()) {
                sb.append("：").append(spec.description().trim());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 是否存在指定名称的工具。 */
    public boolean hasTool(String toolName) {
        return toolToServer.containsKey(toolName);
    }

    /** 执行一次 tool-call，按工具名路由到对应服务器；失败返回错误文本（不抛出，便于喂回模型）。 */
    public String executeTool(ToolExecutionRequest request) {
        String toolName = request.name();
        String serverId = toolToServer.get(toolName);
        if (serverId == null) {
            return "工具不存在或未连接: " + toolName;
        }
        McpClient client = clients.get(serverId);
        if (client == null) {
            return "工具所属 MCP 服务器未连接: " + serverId;
        }
        try {
            return client.executeTool(request).resultText();
        } catch (Exception e) {
            log.error("执行工具 {} 失败", toolName, e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 联网搜索快捷方法：找到名为 webSearch 的工具（兼容名字含 search 的工具），用 {"query": ...} 调用。
     * 用于"联网开关打开时强制搜一次并注入上下文"的场景。
     */
    public String webSearch(String query) {
        String toolName = resolveSearchToolName();
        if (toolName == null) {
            log.warn("没有可用的联网搜索工具（SearXNG MCP 未连接？）");
            return null;
        }
        String escaped = query.replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"query\":\"" + escaped + "\",\"language\":\"zh-CN\"}";
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .name(toolName)
                .arguments(json)
                .build();
        String result = executeTool(req);
        if (result != null && result.startsWith("工具")) {
            // executeTool 的错误前缀，视为失败
            return null;
        }
        return result;
    }

    private String resolveSearchToolName() {
        if (toolToServer.containsKey("webSearch")) return "webSearch";
        return toolToServer.keySet().stream()
                .filter(n -> n.toLowerCase().contains("search"))
                .findFirst()
                .orElse(null);
    }

    public List<ServerStatus> statuses() {
        return new ArrayList<>(statuses.values());
    }

    public ServerStatus status(String id) {
        return statuses.get(id);
    }

    /** 测试单个配置（不影响运行中的客户端）：尝试连接并列出工具。 */
    public Map<String, Object> test(McpServerConfig cfg) {
        Map<String, Object> result = new LinkedHashMap<>();
        McpClient client = null;
        try {
            client = buildClient(cfg);
            List<ToolSpecification> tools = client.listTools();
            result.put("ok", true);
            result.put("toolCount", tools.size());
            result.put("tools", tools.stream().map(ToolSpecification::name).toList());
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    private void closeAll() {
        for (McpClient client : clients.values()) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 MCP Client 出错: {}", e.getMessage());
            }
        }
        clients.clear();
    }

    @PreDestroy
    public void destroy() {
        closeAll();
        log.info("所有 MCP Client 已关闭");
    }
}
