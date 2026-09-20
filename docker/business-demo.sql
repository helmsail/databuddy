-- ============================================================
-- DataBuddy 演示业务库(biz_demo):一个可完整走通
-- 绑表 → Schema 召回 → 表关系 → NL2SQL → Python → 报告 的数据源
-- 三张表带外键链路:orders → customers / products
--
-- 导入(容器内 MySQL;接入指引见文末):
--   docker exec -i databuddy-mysql-1 mysql -uroot -proot --default-character-set=utf8mb4 < docker/business-demo.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS biz_demo DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE biz_demo;

DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS customers;

-- 客户表
CREATE TABLE customers (
  id            BIGINT       PRIMARY KEY COMMENT '客户ID',
  customer_name VARCHAR(64)  NOT NULL COMMENT '客户名称',
  city          VARCHAR(32)  NOT NULL COMMENT '城市',
  register_time DATETIME     NOT NULL COMMENT '注册时间'
) ENGINE=InnoDB COMMENT='客户表';

-- 产品表
CREATE TABLE products (
  id           BIGINT        PRIMARY KEY COMMENT '产品ID',
  product_name VARCHAR(64)   NOT NULL COMMENT '产品名',
  category     VARCHAR(32)   NOT NULL COMMENT '品类',
  unit_price   DECIMAL(10,2) NOT NULL COMMENT '单价(元)'
) ENGINE=InnoDB COMMENT='产品表';

-- 订单表
CREATE TABLE orders (
  id          BIGINT        PRIMARY KEY COMMENT '订单ID',
  customer_id BIGINT        NOT NULL COMMENT '客户ID;关联 customers.id',
  product_id  BIGINT        NOT NULL COMMENT '产品ID;关联 products.id',
  quantity    INT           NOT NULL COMMENT '数量',
  amount      DECIMAL(12,2) NOT NULL COMMENT '订单金额(元)',
  channel     VARCHAR(32)   NOT NULL COMMENT '来源渠道:线上广告/线下门店/合作伙伴/直播带货',
  order_time  DATETIME      NOT NULL COMMENT '下单时间',
  status      VARCHAR(16)   NOT NULL COMMENT '状态:已支付/已退款'
) ENGINE=InnoDB COMMENT='订单表';

INSERT INTO customers (id, customer_name, city, register_time) VALUES
(1,'华辰科技','北京','2025-11-02 10:00:00'),
(2,'蓝湾贸易','上海','2025-12-15 14:30:00'),
(3,'星辰制造','广州','2026-01-08 09:20:00'),
(4,'云图数据','深圳','2026-02-11 16:45:00'),
(5,'恒瑞物流','成都','2026-02-27 11:10:00'),
(6,'青禾农业','杭州','2026-03-19 15:05:00'),
(7,'锐创电子','武汉','2026-04-02 10:40:00'),
(8,'南风文创','西安','2026-05-21 13:25:00');

INSERT INTO products (id, product_name, category, unit_price) VALUES
(1,'智能音箱 A1','智能硬件',399.00),
(2,'智能音箱 A1 Pro','智能硬件',699.00),
(3,'无线耳机 B2','数码配件',499.00),
(4,'智能手表 C3','可穿戴',1299.00),
(5,'智能门锁 D1','智能家居',899.00),
(6,'家庭摄像头 E2','智能家居',259.00);

-- 订单:180 单,覆盖 2026-03-01 ~ 2026-09-08(确定性生成,重复执行结果一致)
INSERT INTO orders (id, customer_id, product_id, quantity, amount, channel, order_time, status)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 180)
SELECT
  n,
  (n % 8) + 1,
  p.id,
  (n % 5) + 1,
  ROUND(((n % 5) + 1) * p.unit_price * (0.90 + ((n * 13) % 21) / 100.0), 2),
  ELT((n % 4) + 1, '线上广告', '线下门店', '合作伙伴', '直播带货'),
  TIMESTAMPADD(DAY, (n * 190) DIV 180, '2026-03-01 09:00:00') + INTERVAL ((n * 7) % 10) HOUR,
  IF(n % 17 = 0, '已退款', '已支付')
FROM seq
JOIN products p ON p.id = (n % 6) + 1;

-- —— 接入指引 ——
-- 1) 前端「配置 → 业务库」新增连接(按应用运行方式二选一):
--      容器化应用(docker compose up): jdbc:mysql://mysql:3306/biz_demo
--      本机直跑(mvnw spring-boot:run): jdbc:mysql://localhost:3306/biz_demo
--    用户名 root;密码见 .env(MYSQL_ROOT_PASSWORD,开发口径 root)
-- 2)「配置 → 智能体 → 管理」把 customers / products / orders 三张表全部绑定,等状态变为 SYNCED
-- 3) (可选)表关系:业务库配置创建后拿到其 id(databuddy.biz_database_config),
--    将下面两行取消注释并用实际 id 替换 <CONFIG_ID> 后执行:
--    INSERT INTO databuddy.biz_table_relation
--      (database_config_id, source_table_name, source_column_name, target_table_name, target_column_name, relation_type)
--    VALUES
--      (<CONFIG_ID>, 'orders', 'customer_id', 'customers', 'id', 'MANY_TO_ONE'),
--      (<CONFIG_ID>, 'orders', 'product_id',  'products',  'id', 'MANY_TO_ONE');
-- 4) 推荐提问:上个月各渠道的订单总额是多少?/ 各城市的客户数量排名 / 最畅销的三个产品 /
--    智能家居品类的月度销售趋势(出图)/ 退款订单占比是多少
