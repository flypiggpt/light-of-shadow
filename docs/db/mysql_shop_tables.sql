-- 增量脚本：在已有 light_of_shadow 数据库里创建商城相关表
-- 执行前请确认已先执行 mysql_schema.sql（至少包含 sys_user）

USE `light_of_shadow`;

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

-- 注意：若需要添加 `sys_user.role` 字段，请运行 mysql_add_user_role.sql（包含安全检查）

CREATE TABLE IF NOT EXISTS `cart_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '购物车项ID',
  `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `product_id` VARCHAR(64) NOT NULL COMMENT '商品ID',
  `quantity` INT NOT NULL COMMENT '数量',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cart_item_user_product` (`user_id`, `product_id`),
  KEY `idx_cart_item_user_id` (`user_id`),
  KEY `idx_cart_item_product_id` (`product_id`),
  CONSTRAINT `fk_cart_item_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
    ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_cart_item_product_id`
    FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='购物车表';

CREATE TABLE IF NOT EXISTS `shop_order` (
  `id` VARCHAR(64) NOT NULL COMMENT '订单ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `address_id` VARCHAR(64) DEFAULT NULL COMMENT '收货地址ID',
  `remark` VARCHAR(255) DEFAULT NULL COMMENT '备注',
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '订单状态',
  `total_cent` INT NOT NULL COMMENT '订单总金额，分',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop_order_no` (`order_no`),
  KEY `idx_shop_order_user_id` (`user_id`),
  KEY `idx_shop_order_status` (`status`),
  KEY `idx_shop_order_created_at` (`created_at`),
  CONSTRAINT `fk_shop_order_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
    ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单表';

CREATE TABLE IF NOT EXISTS `shop_order_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单项ID',
  `order_id` VARCHAR(64) NOT NULL COMMENT '订单ID',
  `product_id` VARCHAR(64) NOT NULL COMMENT '商品ID',
  `product_name` VARCHAR(255) NOT NULL COMMENT '商品名称快照',
  `unit_price_cent` INT NOT NULL COMMENT '成交单价，分',
  `quantity` INT NOT NULL COMMENT '数量',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_shop_order_item_order_id` (`order_id`),
  KEY `idx_shop_order_item_product_id` (`product_id`),
  CONSTRAINT `fk_shop_order_item_order_id`
    FOREIGN KEY (`order_id`) REFERENCES `shop_order` (`id`)
    ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_shop_order_item_product_id`
    FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
    ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单项表';

