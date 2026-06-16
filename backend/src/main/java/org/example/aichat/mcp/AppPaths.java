package org.example.aichat.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 统一解析项目相关路径。
 *
 * 开发：后端以 backend/ 为 cwd，项目根为父目录。
 * 打包：Electron 注入 AI_CHAT_HOME（只读 runtime）与 AI_CHAT_DATA（可写数据），
 *       config 优先落在 AI_CHAT_DATA/config。
 */
@Slf4j
@Component
public class AppPaths {

    @Value("${app.project-root:}")
    private String configuredRoot;

    @Value("${app.user-data-dir:}")
    private String userDataDir;

    @Value("${app.config-dir:config}")
    private String configDirName;

    public Path projectRoot() {
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            return Paths.get(configuredRoot).toAbsolutePath().normalize();
        }
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (cwd.getFileName() != null && "backend".equals(cwd.getFileName().toString())) {
            return cwd.getParent();
        }
        return cwd;
    }

    public Path userDataRoot() {
        if (userDataDir != null && !userDataDir.isBlank()) {
            return Paths.get(userDataDir).toAbsolutePath().normalize();
        }
        String env = System.getenv("AI_CHAT_DATA");
        if (env != null && !env.isBlank()) {
            return Paths.get(env).toAbsolutePath().normalize();
        }
        return null;
    }

    public Path configDir() {
        Path userData = userDataRoot();
        if (userData != null) {
            Path dir = userData.resolve(configDirName).normalize();
            try {
                Files.createDirectories(dir);
            } catch (Exception e) {
                log.warn("创建用户 config 目录失败: {}", e.getMessage());
            }
            return dir;
        }
        Path dir = Paths.get(configDirName);
        if (!dir.isAbsolute()) {
            dir = projectRoot().resolve(dir);
        }
        return dir.normalize();
    }

    public Path mcpServersFile() {
        return configDir().resolve("mcp-servers.json");
    }

    public Path runtimeConfigFile() {
        return configDir().resolve("runtime-config.json");
    }

    public Path skillsDir() {
        return configDir().resolve("skills");
    }

    public Path resolveAgainstRoot(String raw) {
        Path p = Paths.get(raw);
        if (!p.isAbsolute()) {
            p = projectRoot().resolve(p);
        }
        return p.normalize();
    }

    /** 打包模式下 MCP/后端使用的 java 可执行文件（若存在 bundled JRE）。 */
    public String bundledJavaCommand() {
        Path jreJava = projectRoot().resolve("jre/bin/java");
        if (Files.isExecutable(jreJava)) {
            return jreJava.toAbsolutePath().toString();
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Path winJava = projectRoot().resolve("jre/bin/java.exe");
            if (Files.isExecutable(winJava)) {
                return winJava.toAbsolutePath().toString();
            }
        }
        return "java";
    }

    public boolean isPackagedLayout() {
        return userDataRoot() != null || (configuredRoot != null && !configuredRoot.isBlank());
    }
}
