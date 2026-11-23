-- 主动提醒事件表（Proactive Agent）
-- 前置：mysql_shoppingguide.sql（sg_session / sg_message）
USE `light_of_shadow`;

CREATE TABLE IF NOT EXISTS `proactive_event` (
  `id` VARCHAR(64) NOT NULL COMMENT '信号ID（pe_ 前缀）',
  `user_id` VARCHAR(64) NOT NULL,
  `type` VARCHAR(32) NOT NULL COMMENT 'UNPAID_ORDER/DUP_ORDER/CART_IDLE/PRICE_DROP',
  `dedupe_key` VARCHAR(128) NOT NULL COMMENT '幂等键，唯一约束',
  `ref_id` VARCHAR(64) NOT NULL COMMENT '关联订单ID或商品ID',
  `payload_json` TEXT NOT NULL COMMENT '事实字段快照（商品名/金额/天数等）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/CONFIRMED/DISMISSED/EXPIRED',
  `action_id` VARCHAR(32) DEFAULT NULL COMMENT '用户确认的动作',
  `session_id` VARCHAR(64) DEFAULT NULL COMMENT '写入的导购会话',
  `proposed_at` DATETIME(3) DEFAULT NULL,
  `resolved_at` DATETIME(3) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_proactive_dedupe` (`dedupe_key`),
  KEY `idx_proactive_user_status` (`user_id`, `status`, `created_at`),
  KEY `idx_proactive_type_status` (`type`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主动提醒事件';
