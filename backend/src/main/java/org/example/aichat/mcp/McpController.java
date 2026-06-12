package org.example.aichat.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器管理 REST API，供前端"设置 - MCP 服务器"界面增删改查、热重载、连通性测试。
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpServerStore store;
    private final McpClientManager manager;

    public McpController(McpServerStore store, McpClientManager manager) {
        this.store = store;
        this.manager = manager;
    }

    /** 服务器列表（含运行时状态）。 */
    @GetMapping("/servers")
    public List<Map<String, Object>> listServers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpServerConfig cfg : store.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("config", cfg);
            m.put("status", manager.status(cfg.getId()));
            result.add(m);
        }
        return result;
    }

    @GetMapping("/servers/{id}")
    public ResponseEntity<McpServerConfig> getServer(@PathVariable String id) {
        return store.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** 新增或更新一个 MCP 服务器，并热重载注册中心。 */
    @PostMapping("/servers")
    public Map<String, Object> saveServer(@RequestBody McpServerConfig config) {
        McpServerConfig saved = store.upsert(config);
        manager.reload();
        return Map.of("config", saved, "status", manager.status(saved.getId()));
    }

    @PutMapping("/servers/{id}")
    public Map<String, Object> updateServer(@PathVariable String id, @RequestBody McpServerConfig config) {
        config.setId(id);
        McpServerConfig saved = store.upsert(config);
        manager.reload();
        return Map.of("config", saved, "status", manager.status(saved.getId()));
    }

    @DeleteMapping("/servers/{id}")
    public ResponseEntity<Map<String, Object>> deleteServer(@PathVariable String id) {
        boolean ok = store.delete(id);
        if (!ok) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "内置服务器不可删除，或不存在"));
        }
        manager.reload();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 测试某配置能否连通（不落库、不影响运行中的客户端）。 */
    @PostMapping("/servers/test")
    public Map<String, Object> testServer(@RequestBody McpServerConfig config) {
        return manager.test(config);
    }

    /** 热重载所有 MCP 服务器。 */
    @PostMapping("/reload")
    public List<McpClientManager.ServerStatus> reload() {
        manager.reload();
        return manager.statuses();
    }

    /** 当前聚合可用的全部工具。 */
    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolSpecification spec : manager.listAllTools()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", spec.name());
            m.put("description", spec.description());
            result.add(m);
        }
        return result;
    }
}
