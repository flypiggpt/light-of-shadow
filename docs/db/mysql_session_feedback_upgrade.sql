-- 存量库升级：sg_session 扩展状态列 + 导购反馈表
-- 新装库请直接执行更新后的 mysql_shoppingguide.sql

ALTER TABLE `sg_session`
  ADD COLUMN `summary` TEXT COMMENT '会话摘要' AFTER `welcome_message`,
  ADD COLUMN `last_intent` TEXT COMMENT '最近意图 JSON' AFTER `summary`,
  ADD COLUMN `recommendations_json` TEXT COMMENT '最近推荐 JSON' AFTER `last_intent`;

CREATE TABLE IF NOT EXISTS `sg_feedback` (
  `id` VARCHAR(64) NOT NULL COMMENT '反馈ID',
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
  `message_id` VARCHAR(64) DEFAULT NULL COMMENT '关联消息ID',
  `rating` INT NOT NULL COMMENT '评分 1-5',
  `feedback` VARCHAR(500) DEFAULT NULL COMMENT '文本反馈',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_sg_feedback_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='导购反馈';
