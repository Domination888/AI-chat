package org.example.aichat.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 统一解析项目相关路径。
 *
 * 后端通常以 backend/ 作为工作目录（mvnw spring-boot:run），但脚本也可能在项目根启动，
 * 这里统一把"项目根"规整出来，让 config/ 目录、各 MCP jar 的相对路径在两种情况下都能解析正确。
 */
@Slf4j
@Component
public class AppPaths {

    /** 可显式覆盖项目根（绝对路径）；留空则自动推断 */
    @Value("${app.project-root:}")
    private String configuredRoot;

    /** config 目录（相对项目根） */
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

    public Path configDir() {
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

    /** 把相对路径（如 services/xxx/target/xxx.jar）解析为基于项目根的绝对路径。 */
    public Path resolveAgainstRoot(String raw) {
        Path p = Paths.get(raw);
        if (!p.isAbsolute()) {
            p = projectRoot().resolve(p);
        }
        return p.normalize();
    }
}
