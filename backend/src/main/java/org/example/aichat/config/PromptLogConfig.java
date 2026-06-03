package org.example.aichat.config;

import org.example.aichat.util.PromptLogger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class PromptLogConfig {

    /** 项目根目录，默认 backend 的上级目录 */
    @Value("${app.log-base-dir:${user.dir}/../unified-logs}")
    private String logBaseDir;

    @Bean
    public PromptLogger promptLogger() {
        Path baseDir = Path.of(logBaseDir).resolve("backend");
        return new PromptLogger(baseDir);
    }
}