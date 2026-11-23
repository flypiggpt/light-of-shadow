-- 用户提醒偏好表（Proactive Agent）
-- 前置：mysql_proactive_event.sql
USE `light_of_shadow`;

CREATE TABLE IF NOT EXISTS `proactive_pref` (
  `user_id` VARCHAR(64) NOT NULL,
  `type` VARCHAR(32) NOT NULL COMMENT 'ALL（总开关）或信号类型',
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主动提醒偏好';
