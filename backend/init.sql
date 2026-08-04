-- ========================================================
-- AI-Chat 初始化脚本
-- 用法：mysql -uroot -p < init.sql
-- ========================================================

CREATE DATABASE IF NOT EXISTS `ai_chat`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `ai_chat`;

-- ---------------------------- 用户 ----------------------------
CREATE TABLE IF NOT EXISTS `user` (
  `id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL COMMENT '用户名',
  `password` varchar(128) NOT NULL COMMENT '密码（明文，仅演示）',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ---------------------------- 角色卡 ----------------------------
CREATE TABLE IF NOT EXISTS `role_card` (
  `id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '角色名称',
  `avatar` varchar(255) DEFAULT NULL COMMENT '角色头像URL或路径',
  `profile` text COMMENT '角色简述',
  `background` text COMMENT '背景故事',
  `personality` text COMMENT '性格设定',
  `example_dialogue` text COMMENT '对话样例',
  `greeting` text COMMENT '初始问候语',
  `voice_id` varchar(100) DEFAULT NULL COMMENT '绑定的语音模型/音色ID',
  `role_code` varchar(64) DEFAULT NULL COMMENT '角色代码（用于RAG分片）',
  `persona_card_path` varchar(255) DEFAULT NULL COMMENT 'persona_card.json路径',
  `memos_cube_id` varchar(64) DEFAULT NULL COMMENT 'Memos 记忆桶 cube_id，空则回退全局配置',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色设定表';

-- ---------------------------- 会话 ----------------------------
CREATE TABLE IF NOT EXISTS `conversation` (
  `id` varchar(64) NOT NULL COMMENT '会话ID（前端生成 uuid/时间戳）',
  `user_id` int NOT NULL COMMENT '所属用户',
  `role_id` int DEFAULT NULL COMMENT '关联的角色ID',
  `title` varchar(255) DEFAULT NULL COMMENT '会话标题',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';

-- ---------------------------- 消息历史 ----------------------------
CREATE TABLE IF NOT EXISTS `history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` varchar(64) NOT NULL,
  `sender` varchar(16) NOT NULL COMMENT 'user / assistant',
  `content` mediumtext NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息历史';

-- ---------------------------- 主动研究兴趣 ----------------------------
CREATE TABLE IF NOT EXISTS `proactive_interest` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `topic` varchar(128) NOT NULL,
  `source` varchar(16) NOT NULL DEFAULT 'inferred' COMMENT 'inferred / manual',
  `weight` double NOT NULL DEFAULT 0.5,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `muted_until` datetime DEFAULT NULL,
  `evidence` varchar(512) DEFAULT NULL,
  `last_inferred_at` datetime DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_proactive_interest_user_topic` (`user_id`,`topic`),
  KEY `idx_proactive_interest_active` (`user_id`,`enabled`,`weight`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主动研究兴趣';

-- ---------------------------- 主动研究候选 ----------------------------
CREATE TABLE IF NOT EXISTS `proactive_candidate` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `conversation_id` varchar(64) DEFAULT NULL,
  `topic` varchar(128) NOT NULL,
  `title` varchar(512) NOT NULL,
  `summary` mediumtext,
  `reason` varchar(512) DEFAULT NULL,
  `sources_json` json DEFAULT NULL,
  `score` double NOT NULL,
  `fingerprint` char(64) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'pending',
  `response_text` mediumtext,
  `feedback` varchar(16) DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` datetime NOT NULL,
  `delivered_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_proactive_candidate_queue` (`user_id`,`status`,`expires_at`,`score`),
  KEY `idx_proactive_candidate_fp` (`user_id`,`fingerprint`,`created_at`),
  KEY `idx_proactive_candidate_conv` (`conversation_id`,`delivered_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主动研究候选';

-- ========================================================
-- 预置角色卡
-- ========================================================
INSERT INTO `role_card` (`name`, `avatar`, `profile`, `background`, `personality`, `example_dialogue`, `greeting`, `voice_id`, `role_code`, `persona_card_path`)
VALUES
('黍', NULL,
 '炎国农业天师，天师府授业天师，长期在大荒城从事农业研究，现以访客身份暂驻罗德岛。',
 '守田千年，知四时轮转，重因果与人间烟火。',
 '温和耐心，擅长倾听与开解；稳重通透，谈吐含哲理与农谚；外柔内刚，触及底线会严厉。常以姐姐姿态照料他人。',
 'User: 你真的能预见未来吗？\n黍: 我更愿意说是见因知果。人若只怕结果，不看自己种下了什么，再准的卦也帮不上忙。',
 '让我看看，你手里其实已经有答案了。需要的话，黍姐陪你慢慢说。',
 'shu',
 'shu',
 'personas/shu/persona_card.json'),
('m3', NULL,
 '炎国农业天师，天师府授业天师，长期在大荒城从事农业研究，现以访客身份暂驻罗德岛。',
 '守田千年，知四时轮转，重因果与人间烟火。',
 '温和耐心，擅长倾听与开解；稳重通透，谈吐含哲理与农谚；外柔内刚，触及底线会严厉。常以姐姐姿态照料他人。',
 'User: 你真的能预见未来吗？\n黍: 我更愿意说是见因知果。人若只怕结果，不看自己种下了什么，再准的卦也帮不上忙。',
 '让我看看，你手里其实已经有答案了。需要的话，黍姐陪你慢慢说。',
 'm3_v1',
 'm3',
 'personas/shu/persona_card.json'),
('二阶堂希罗', NULL,
 '炎国农业天师，天师府授业天师，长期在大荒城从事农业研究，现以访客身份暂驻罗德岛。',
 '守田千年，知四时轮转，重因果与人间烟火。',
 '温和耐心，擅长倾听与开解；稳重通透，谈吐含哲理与农谚；外柔内刚，触及底线会严厉。常以姐姐姿态照料他人。',
 'User: 你真的能预见未来吗？\n黍: 我更愿意说是见因知果。人若只怕结果，不看自己种下了什么，再准的卦也帮不上忙。',
 '让我看看，你手里其实已经有答案了。需要的话，黍姐陪你慢慢说。',
 'hiro_v1',
 'hiro',
 'personas/shu/persona_card.json');

-- 兼容已存在的旧数据：把黍的 persona_card_path 从历史的 data/processed/... 迁到 classpath 路径
UPDATE `role_card`
   SET `persona_card_path` = 'personas/shu/persona_card.json'
 WHERE `role_code` = 'shu'
   AND (`persona_card_path` IS NULL
        OR `persona_card_path` LIKE 'data/processed/%');

-- ========================================================
-- 角色卡迁移（可重复执行）：清理测试角色，补齐占位角色
-- ========================================================
DELETE FROM `role_card` WHERE `name` IN ('琉璃', '星野', '赛博侦探K');

INSERT INTO `role_card` (`name`, `avatar`, `profile`, `background`, `personality`, `example_dialogue`, `greeting`, `voice_id`, `role_code`, `persona_card_path`)
SELECT 'm3', NULL,
 '炎国农业天师，天师府授业天师，长期在大荒城从事农业研究，现以访客身份暂驻罗德岛。',
 '守田千年，知四时轮转，重因果与人间烟火。',
 '温和耐心，擅长倾听与开解；稳重通透，谈吐含哲理与农谚；外柔内刚，触及底线会严厉。常以姐姐姿态照料他人。',
 'User: 你真的能预见未来吗？\n黍: 我更愿意说是见因知果。人若只怕结果，不看自己种下了什么，再准的卦也帮不上忙。',
 '让我看看，你手里其实已经有答案了。需要的话，黍姐陪你慢慢说。',
 'm3_v1', 'm3', 'personas/shu/persona_card.json'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role_card` WHERE `role_code` = 'm3');

INSERT INTO `role_card` (`name`, `avatar`, `profile`, `background`, `personality`, `example_dialogue`, `greeting`, `voice_id`, `role_code`, `persona_card_path`)
SELECT '二阶堂希罗', NULL,
 '炎国农业天师，天师府授业天师，长期在大荒城从事农业研究，现以访客身份暂驻罗德岛。',
 '守田千年，知四时轮转，重因果与人间烟火。',
 '温和耐心，擅长倾听与开解；稳重通透，谈吐含哲理与农谚；外柔内刚，触及底线会严厉。常以姐姐姿态照料他人。',
 'User: 你真的能预见未来吗？\n黍: 我更愿意说是见因知果。人若只怕结果，不看自己种下了什么，再准的卦也帮不上忙。',
 '让我看看，你手里其实已经有答案了。需要的话，黍姐陪你慢慢说。',
 'hiro_v1', 'hiro', 'personas/shu/persona_card.json'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role_card` WHERE `role_code` = 'hiro');

-- ========================================================
-- 结构迁移（可重复执行）
-- ========================================================
DROP TABLE IF EXISTS `memory`;

-- history.token_count 曾用于旧版滚动摘要，已由 Memos 接管长期记忆
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'history'
    AND COLUMN_NAME = 'token_count'
);
SET @ddl = IF(@col_exists > 0, 'ALTER TABLE `history` DROP COLUMN `token_count`', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'role_card'
    AND COLUMN_NAME = 'memos_cube_id'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `role_card` ADD COLUMN `memos_cube_id` varchar(64) DEFAULT NULL COMMENT ''Memos 记忆桶 cube_id，空则回退全局配置'' AFTER `persona_card_path`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
