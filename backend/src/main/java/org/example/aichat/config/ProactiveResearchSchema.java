package org.example.aichat.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProactiveResearchSchema {
    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS proactive_interest (
                      id BIGINT NOT NULL AUTO_INCREMENT,user_id INT NOT NULL,topic VARCHAR(128) NOT NULL,
                      source VARCHAR(16) NOT NULL DEFAULT 'inferred',weight DOUBLE NOT NULL DEFAULT 0.5,
                      enabled TINYINT(1) NOT NULL DEFAULT 1,muted_until DATETIME NULL,evidence VARCHAR(512) NULL,
                      last_inferred_at DATETIME NULL,created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY(id),UNIQUE KEY uk_proactive_interest_user_topic(user_id,topic),
                      KEY idx_proactive_interest_active(user_id,enabled,weight)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS proactive_candidate (
                      id BIGINT NOT NULL AUTO_INCREMENT,user_id INT NOT NULL,conversation_id VARCHAR(64) NULL,
                      topic VARCHAR(128) NOT NULL,title VARCHAR(512) NOT NULL,summary MEDIUMTEXT NULL,
                      reason VARCHAR(512) NULL,sources_json JSON NULL,score DOUBLE NOT NULL,fingerprint CHAR(64) NOT NULL,
                      status VARCHAR(16) NOT NULL DEFAULT 'pending',response_text MEDIUMTEXT NULL,feedback VARCHAR(16) NULL,
                      created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,expires_at DATETIME NOT NULL,delivered_at DATETIME NULL,
                      PRIMARY KEY(id),KEY idx_proactive_candidate_queue(user_id,status,expires_at,score),
                      KEY idx_proactive_candidate_fp(user_id,fingerprint,created_at),
                      KEY idx_proactive_candidate_conv(conversation_id,delivered_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        } catch (Exception e) {
            log.warn("主动研究表初始化失败；可重新执行 backend/init.sql: {}", e.getMessage());
        }
    }
}
