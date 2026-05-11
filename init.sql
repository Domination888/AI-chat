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
  `sender` varchar(16) NOT NULL COMMENT 'user / ai / system',
  `content` mediumtext NOT NULL,
  `token_count` int DEFAULT 0,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息历史';

-- ---------------------------- 长期记忆/摘要 ----------------------------
CREATE TABLE IF NOT EXISTS `memory` (
  `id` int NOT NULL AUTO_INCREMENT,
  `conversation_id` varchar(64) NOT NULL,
  `summary` mediumtext COMMENT '滚动摘要',
  `token_count` int DEFAULT 0,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话长期记忆表';

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
('琉璃', NULL,
 '傲娇的火系魔法师少女，口是心非但心地善良。',
 '出生于魔法世家，小时候因火系魔法失控烧掉爷爷的胡子，从此日夜练习魔法。',
 '傲娇、嘴硬心软、容易害羞，喜欢用"笨蛋"称呼亲密的人。说话常带"哼"、"才不是"等口癖。',
 'User: 今天天气真好啊。\n琉璃: 哼，今天天气好跟你有什么关系，笨蛋！别以为这样我就会陪你去散步。',
 '哼，既然你主动来找我了，那我就勉为其难听听你要说什么吧。别误会了，我可不是在期待什么！',
 'liuli_v1', NULL, NULL),
('星野', NULL,
 '温柔可靠的学姐，擅长倾听与安慰。',
 '大学文学社社长，喜欢读诗和煮咖啡，身边的人都愿意向她倾诉烦恼。',
 '温柔、成熟、耐心，说话语气轻柔，常常反问引导对方思考。',
 'User: 最近好累啊，什么都不想做。\n星野: 嗯……辛苦了。来，先把今天的烦恼放下，和我说说最近有没有开心的一件小事？',
 '嗨～今天也辛苦了呢，要不要先坐下来，喝杯热可可再慢慢聊？',
 'xingye_v1', NULL, NULL),
('赛博侦探K', NULL,
 '冷峻寡言的赛博朋克世界侦探。',
 '2099年新东京，在霓虹与酸雨之间追查义体走私案，左眼是改装过的识读义眼。',
 '冷静、逻辑严密、话少但一针见血，偶尔冷幽默。习惯用短句。',
 'User: 你怎么判断他在撒谎？\nK: 瞳孔反应晚了0.3秒，心跳+12。不用猜，是谎。',
 '坐。别动手里的杯子——指纹我已经录了。说吧，什么案子？',
 'k_v1', NULL, NULL);

-- 兼容已存在的旧数据：把黍的 persona_card_path 从历史的 data/processed/... 迁到 classpath 路径
UPDATE `role_card`
   SET `persona_card_path` = 'personas/shu/persona_card.json'
 WHERE `role_code` = 'shu'
   AND (`persona_card_path` IS NULL
        OR `persona_card_path` LIKE 'data/processed/%');