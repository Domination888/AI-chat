package org.example.aichat.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个 MCP 服务器的配置定义。持久化为 config/mcp-servers.json 中的一项，可由前端增删改。
 *
 * 两种传输方式：
 *   - stdio：本地子进程，通过 {@link #command} + {@link #env} 启动（如本地 jar / npx 包）
 *   - sse  ：远程 HTTP SSE 服务，通过 {@link #url} + {@link #headers} 连接
 */
@Data
public class McpServerConfig {

    /** 唯一 ID（前端不传则后端按 name 生成） */
    private String id;

    /** 展示名 */
    private String name;

    /** 说明 */
    private String description = "";

    /** 是否启用；false 时不会被 McpClientManager 拉起 */
    private boolean enabled = true;

    /** 传输方式：stdio | sse */
    private String transport = "stdio";

    /** stdio：完整启动命令（如 ["java","-jar","mcp/example.jar"]） */
    private List<String> command = new ArrayList<>();

    /** stdio：环境变量 */
    private Map<String, String> env = new LinkedHashMap<>();

    /** sse：SSE endpoint URL */
    private String url = "";

    /** sse：自定义请求头（如鉴权） */
    private Map<String, String> headers = new LinkedHashMap<>();

    /** 内置服务器（如 prime），不允许删除，只能启用/停用与改参 */
    private boolean builtin = false;

    @JsonIgnore
    public boolean isStdio() {
        return "stdio".equalsIgnoreCase(transport);
    }

    @JsonIgnore
    public boolean isSse() {
        return "sse".equalsIgnoreCase(transport);
    }
}
