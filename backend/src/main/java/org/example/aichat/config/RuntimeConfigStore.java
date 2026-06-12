package org.example.aichat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.example.aichat.mcp.AppPaths;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 运行时配置持久化：读写 config/runtime-config.json。
 */
@Slf4j
@Component
public class RuntimeConfigStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final AppPaths appPaths;
    private final Object lock = new Object();

    public RuntimeConfigStore(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    public Optional<RuntimeConfig> load() {
        synchronized (lock) {
            Path file = appPaths.runtimeConfigFile();
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            try {
                return Optional.of(MAPPER.readValue(Files.readAllBytes(file), RuntimeConfig.class));
            } catch (IOException e) {
                log.error("读取运行时配置失败 {}: {}", file, e.getMessage());
                return Optional.empty();
            }
        }
    }

    public void save(RuntimeConfig config) {
        synchronized (lock) {
            Path file = appPaths.runtimeConfigFile();
            try {
                Files.createDirectories(file.getParent());
                MAPPER.writeValue(file.toFile(), config);
                log.info("已保存运行时配置: {}", file);
            } catch (IOException e) {
                throw new IllegalStateException("保存运行时配置失败: " + file, e);
            }
        }
    }
}
