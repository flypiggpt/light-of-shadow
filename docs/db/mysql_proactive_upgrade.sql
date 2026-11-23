-- 存量库升级：proactive_event 扩展提议快照列（PR1 建表后本升级补列）
-- 新装库请在 mysql_proactive_event.sql 基础上追加同名列
USE `light_of_shadow`;

ALTER TABLE `proactive_event`
  ADD COLUMN `proposal_json` TEXT COMMENT '提议快照（message + actions）';
