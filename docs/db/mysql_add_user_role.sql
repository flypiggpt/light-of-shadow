-- 将用户表扩展为包含 role 字段（USER/MERCHANT/ADMIN）
-- 使用方式：在你的 MySQL 中执行此脚本，或将其合并到迁移工具中

USE `light_of_shadow`;

DELIMITER $$
DROP PROCEDURE IF EXISTS add_role_column$$
CREATE PROCEDURE add_role_column()
BEGIN
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'role') = 0 THEN
    ALTER TABLE `sys_user`
      ADD COLUMN `role` VARCHAR(32) NOT NULL DEFAULT 'USER' COMMENT '用户角色：USER/MERCHANT/ADMIN';
  END IF;
END$$
DELIMITER ;

CALL add_role_column();
DROP PROCEDURE IF EXISTS add_role_column;

-- 示例：把某个用户提升为商家（替换下面的 user id）
-- UPDATE sys_user SET role = 'MERCHANT' WHERE id = 'u_...';

-- 你也可以为现有的特定账号设置商家角色，例如：
-- UPDATE sys_user SET role = 'MERCHANT' WHERE account = 'merchant_account';


