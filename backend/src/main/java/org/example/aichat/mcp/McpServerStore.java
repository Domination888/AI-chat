package org.example.aichat.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 服务器配置的持久化层：读写 config/mcp-servers.json。
 * 首次启动若文件不存在，则写入内置默认项（searxng 联网搜索 + prime 示例）。
 */
@Slf4j
@Component
public class McpServerStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final AppPaths appPaths;
    private final Object lock = new Object();

    public McpServerStore(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    @PostConstruct
    public void init() {
        Path file = appPaths.mcpServersFile();
        if (!Files.exists(file)) {
            log.info("未发现 MCP 配置 {}，写入内置默认项", file);
            saveAll(defaults());
        } else {
            log.info("加载 MCP 配置: {}", file);
        }
    }

    public List<McpServerConfig> list() {
        synchronized (lock) {
            Path file = appPaths.mcpServersFile();
            if (!Files.exists(file)) {
                return defaults();
            }
            try {
                McpServerConfig[] arr = MAPPER.readValue(Files.readAllBytes(file), McpServerConfig[].class);
                List<McpServerConfig> result = new ArrayList<>(List.of(arr));
                // 兜底：确保内置项始终存在（被误删时补回，但保留用户对其的启用/参数修改）
                ensureBuiltins(result);
                return result;
            } catch (IOException e) {
                log.error("读取 MCP 配置失败，返回默认: {}", e.getMessage());
                return defaults();
            }
        }
    }

    public Optional<McpServerConfig> get(String id) {
        return list().stream().filter(c -> c.getId().equals(id)).findFirst();
    }

    /** 新增或更新（按 id）。返回保存后的对象。 */
    public McpServerConfig upsert(McpServerConfig config) {
        synchronized (lock) {
            if (config.getId() == null || config.getId().isBlank()) {
                config.setId(generateId(config.getName()));
            }
            List<McpServerConfig> all = list();
            boolean replaced = false;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(config.getId())) {
                    // 内置标记不可被前端篡改
                    config.setBuiltin(all.get(i).isBuiltin());
                    all.set(i, config);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                config.setBuiltin(false);
                all.add(config);
            }
            saveAll(all);
            return config;
        }
    }

    /** 删除（内置项不可删，返回 false）。 */
    public boolean delete(String id) {
        synchronized (lock) {
            List<McpServerConfig> all = list();
            Optional<McpServerConfig> target = all.stream().filter(c -> c.getId().equals(id)).findFirst();
            if (target.isEmpty()) return false;
            if (target.get().isBuiltin()) return false;
            all.removeIf(c -> c.getId().equals(id));
            saveAll(all);
            return true;
        }
    }

    public void saveAll(List<McpServerConfig> configs) {
        synchronized (lock) {
            try {
                Path file = appPaths.mcpServersFile();
                Files.createDirectories(file.getParent());
                Files.write(file, MAPPER.writeValueAsBytes(configs));
            } catch (IOException e) {
                throw new RuntimeException("写入 MCP 配置失败: " + e.getMessage(), e);
            }
        }
    }

    private void ensureBuiltins(List<McpServerConfig> current) {
        boolean changed = false;
        for (McpServerConfig def : defaults()) {
            boolean exists = current.stream().anyMatch(c -> c.getId().equals(def.getId()));
            if (!exists) {
                current.add(def);
                changed = true;
            }
        }
        if (changed) {
            saveAll(current);
        }
    }

    private String generateId(String name) {
        String base = (name == null || name.isBlank()) ? "mcp" : name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isBlank()) base = "mcp";
        List<McpServerConfig> all = list();
        String id = base;
        int n = 1;
        while (true) {
            final String candidate = id;
            boolean taken = all.stream().anyMatch(c -> c.getId().equals(candidate));
            if (!taken) {
                return id;
            }
            id = base + "-" + (++n);
        }
    }

    /** 内置默认 MCP 服务器。 */
    private List<McpServerConfig> defaults() {
        List<McpServerConfig> list = new ArrayList<>();

        McpServerConfig searxng = new McpServerConfig();
        searxng.setId("searxng");
        searxng.setName("SearXNG 联网搜索");
        searxng.setDescription("本地 SearXNG 元搜索引擎，提供 webSearch 工具（替代智谱 Web Search）");
        searxng.setEnabled(true);
        searxng.setBuiltin(true);
        searxng.setTransport("stdio");
        searxng.setCommand(new ArrayList<>(List.of(
                "java", "-jar",
                "services/searxng-mcp-server/target/searxng-mcp-server-1.0.0.jar",
                "--searxng-url", "http://localhost:8888"
        )));
        list.add(searxng);

        McpServerConfig prime = new McpServerConfig();
        prime.setId("prime");
        prime.setName("Prime 示例工具");
        prime.setDescription("示例本地 MCP 工具：isPrime 判断质数");
        prime.setEnabled(true);
        prime.setBuiltin(true);
        prime.setTransport("stdio");
        prime.setCommand(new ArrayList<>(List.of(
                "java", "-jar",
                "services/prime-mcp-server/target/prime-mcp-server-1.0.0.jar"
        )));
        list.add(prime);

        return list;
    }
}
