CREATE DATABASE IF NOT EXISTS `light_of_shadow`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE `light_of_shadow`;

CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `account` VARCHAR(128) NOT NULL COMMENT '登录账号',
  `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt密码哈希',
  `nickname` VARCHAR(64) NOT NULL COMMENT '昵称',
  `avatar_url` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '头像地址',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_user_account` (`account`),
  KEY `idx_sys_user_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统用户表';

CREATE TABLE IF NOT EXISTS `sys_refresh_token` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `token_hash` CHAR(64) NOT NULL COMMENT 'refreshToken SHA-256 哈希',
  `expires_at` DATETIME(3) NOT NULL COMMENT '过期时间',
  `revoked` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否吊销',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_refresh_token_hash` (`token_hash`),
  KEY `idx_sys_refresh_token_user_id` (`user_id`),
  KEY `idx_sys_refresh_token_expires_at` (`expires_at`),
  CONSTRAINT `fk_sys_refresh_token_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='刷新令牌表';

CREATE TABLE IF NOT EXISTS `product` (
  `id` VARCHAR(64) NOT NULL COMMENT '商品ID',
  `name` VARCHAR(255) NOT NULL COMMENT '商品名称',
  `description` TEXT COMMENT '商品描述',
  `price_cent` INT NOT NULL COMMENT '价格，分',
  `stock` INT NOT NULL DEFAULT 0 COMMENT '库存',
  `cover_url` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '封面图地址',
  `merchant_id` VARCHAR(64) NOT NULL COMMENT '商家/发布者用户ID',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_product_merchant_id` (`merchant_id`),
  KEY `idx_product_created_at` (`created_at`),
  CONSTRAINT `fk_product_merchant_id`
    FOREIGN KEY (`merchant_id`) REFERENCES `sys_user` (`id`)
    ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品表';

