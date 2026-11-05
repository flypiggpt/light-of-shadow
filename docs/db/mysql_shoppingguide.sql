-- 导购会话与消息（消息日志；Agent 状态以后交给 LangGraph checkpoint）
USE `light_of_shadow`;

CREATE TABLE IF NOT EXISTS `sg_session` (
  `id` VARCHAR(64) NOT NULL COMMENT '会话ID',
  `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `entry` VARCHAR(32) NOT NULL DEFAULT 'shop' COMMENT '入口',
  `source` VARCHAR(32) NOT NULL DEFAULT 'app' COMMENT '来源',
  `locale` VARCHAR(16) NOT NULL DEFAULT 'zh-CN' COMMENT '语言',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ENDED',
  `welcome_message` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '欢迎语',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_sg_session_user` (`user_id`),
  KEY `idx_sg_session_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='导购会话';

CREATE TABLE IF NOT EXISTS `sg_message` (
  `id` VARCHAR(64) NOT NULL COMMENT '消息ID',
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
  `role` VARCHAR(16) NOT NULL COMMENT 'user/assistant',
  `content_type` VARCHAR(16) NOT NULL DEFAULT 'text',
  `content` TEXT NOT NULL COMMENT '消息内容',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_sg_message_session` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='导购消息日志';
