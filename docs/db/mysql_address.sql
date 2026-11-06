-- 收货地址
USE `light_of_shadow`;

CREATE TABLE IF NOT EXISTS `user_address` (
  `id` VARCHAR(64) NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `receiver_name` VARCHAR(64) NOT NULL COMMENT '收货人',
  `phone` VARCHAR(32) NOT NULL COMMENT '手机号',
  `detail` VARCHAR(255) NOT NULL COMMENT '详细地址',
  `is_default` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否默认',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_address_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户收货地址';
