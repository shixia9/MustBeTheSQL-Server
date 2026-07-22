-- ============================================================
-- Example Test Database for SQL Agent E2E Testing
-- 传统电商/零售业务数据库
-- 包含 9 张表、外键关系、多种数据类型、敏感数据字段
-- 覆盖 Agent 各阶段测试场景: 简单查询 / JOIN / 聚合 /
-- 时间趋势 / 多步分析 / 敏感数据(HITL) / 图表渲染
-- ============================================================

CREATE DATABASE IF NOT EXISTS example_shop;
USE example_shop;

-- ============================================================
-- 1. 产品分类
-- ============================================================
CREATE TABLE IF NOT EXISTS categories (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL COMMENT '分类名称',
    parent_id   BIGINT DEFAULT NULL COMMENT '父分类ID',
    sort_order  INT DEFAULT 0 COMMENT '排序',
    status      TINYINT DEFAULT 1 COMMENT '1=启用 0=禁用',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_parent (parent_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品分类';

INSERT INTO categories (id, name, parent_id, sort_order, status) VALUES
(1, '电子产品',    NULL, 1, 1),
(2, '手机通讯',    1,    1, 1),
(3, '电脑办公',    1,    2, 1),
(4, '家用电器',    NULL, 2, 1),
(5, '大家电',      4,    1, 1),
(6, '生活电器',    4,    2, 1),
(7, '服装鞋帽',    NULL, 3, 1),
(8, '男装',        7,    1, 1),
(9, '女装',        7,    2, 1),
(10, '食品饮料',   NULL, 4, 1),
(11, '生鲜水果',   10,   1, 1),
(12, '休闲零食',   10,   2, 1);

-- ============================================================
-- 2. 供应商
-- ============================================================
CREATE TABLE IF NOT EXISTS suppliers (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(50)  NOT NULL UNIQUE COMMENT '供应商编码',
    name            VARCHAR(200) NOT NULL COMMENT '供应商名称',
    contact_person  VARCHAR(100) COMMENT '联系人',
    phone           VARCHAR(50)  COMMENT '联系电话',
    province        VARCHAR(50)  COMMENT '省份',
    city            VARCHAR(50)  COMMENT '城市',
    cooperation_rank TINYINT DEFAULT 3 COMMENT '合作等级 1-5',
    status          TINYINT DEFAULT 1,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_rank (cooperation_rank),
    INDEX idx_province (province)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商';

INSERT INTO suppliers (id, code, name, contact_person, phone, province, city, cooperation_rank, status) VALUES
(1, 'SUP-001', '深圳华强电子有限公司',     '张伟',   '0755-12345678', '广东', '深圳', 5, 1),
(2, 'SUP-002', '北京中科电脑科技',         '李强',   '010-87654321',  '北京', '北京', 4, 1),
(3, 'SUP-003', '杭州百世电商供应链',       '王芳',   '0571-11223344', '浙江', '杭州', 5, 1),
(4, 'SUP-004', '广州服装进出口公司',       '赵明',   '020-99887766',  '广东', '广州', 3, 1),
(5, 'SUP-005', '成都川味食品有限公司',     '刘洋',   '028-55667788',  '四川', '成都', 4, 1),
(6, 'SUP-006', '青岛海尔家电集团',         '陈静',   '0532-33445566', '山东', '青岛', 5, 1),
(7, 'SUP-007', '上海日用品商贸有限公司',   '周杰',   '021-99881122',  '上海', '上海', 2, 0);

-- ============================================================
-- 3. 产品
-- ============================================================
CREATE TABLE IF NOT EXISTS products (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku             VARCHAR(50)  NOT NULL UNIQUE COMMENT 'SKU编码',
    name            VARCHAR(200) NOT NULL COMMENT '产品名称',
    category_id     BIGINT NOT NULL COMMENT '所属分类',
    supplier_id     BIGINT DEFAULT NULL COMMENT '主要供应商',
    unit_price      DECIMAL(10,2) NOT NULL COMMENT '单价',
    cost_price      DECIMAL(10,2) NOT NULL COMMENT '成本价',
    stock           INT DEFAULT 0 COMMENT '当前库存',
    min_stock       INT DEFAULT 10 COMMENT '最低库存预警',
    weight_kg       DECIMAL(8,3) DEFAULT 0 COMMENT '重量(kg)',
    status          TINYINT DEFAULT 1 COMMENT '1=上架 0=下架',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category (category_id),
    INDEX idx_supplier (supplier_id),
    INDEX idx_status (status),
    FOREIGN KEY (category_id) REFERENCES categories(id),
    FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品';

INSERT INTO products (id, sku, name, category_id, supplier_id, unit_price, cost_price, stock, min_stock, weight_kg, status) VALUES
-- 手机通讯
(1,  'PHN-001', '华为Mate 60 Pro 512G',      2, 1, 7999.00, 5800.00, 120, 20, 0.225, 1),
(2,  'PHN-002', 'iPhone 15 Pro Max 256G',   2, 1, 9999.00, 7200.00, 85,  15, 0.221, 1),
(3,  'PHN-003', '小米14 Ultra 512G',         2, 1, 5999.00, 4300.00, 200, 30, 0.220, 1),
(4,  'PHN-004', 'OPPO Find X7 Ultra',        2, 1, 5999.00, 4100.00, 65,  10, 0.218, 1),
-- 电脑办公
(5,  'PC-001',  '联想ThinkPad X1 Carbon',   3, 2, 12999.00, 9500.00, 45,  10, 1.120, 1),
(6,  'PC-002',  '苹果MacBook Pro 14寸 M3',  3, 2, 14999.00, 11000.00, 30, 8,  1.600, 1),
(7,  'PC-003',  '戴尔XPS 16 2024款',        3, 2, 13999.00, 10000.00, 22, 5,  1.800, 1),
-- 大家电
(8,  'AC-001',  '海尔3匹变频冷暖空调',      5, 6, 5999.00, 4000.00, 50,  10, 32.000, 1),
(9,  'AC-002',  '美的对开门冰箱600L',       5, 6, 4999.00, 3200.00, 35,  8,  85.000, 1),
(10, 'AC-003',  '格力1.5匹壁挂式空调',      5, 6, 3299.00, 2100.00, 80,  15, 12.500, 1),
-- 生活电器
(11, 'HE-001',  '戴森V15吸尘器',            6, 3, 4990.00, 3300.00, 60,  10, 6.800, 1),
(12, 'HE-002',  '九阳破壁豆浆机',           6, 3, 599.00,  380.00,  150, 20, 3.200, 1),
(13, 'HE-003',  '小米空气净化器4 Pro',      6, 3, 1999.00, 1300.00, 90,  15, 8.500, 1),
-- 男装
(14, 'CL-001',  '海澜之家商务衬衫',         8, 4, 299.00,  120.00,  500, 50, 0.300, 1),
(15, 'CL-002',  '七匹狼纯棉T恤',            8, 4, 199.00,  80.00,   800, 60, 0.250, 1),
-- 女装
(16, 'CL-003',  'ONLY春秋连衣裙',           9, 4, 459.00,  180.00,  350, 40, 0.350, 1),
(17, 'CL-004',  '优衣库女装羽绒服',         9, 4, 799.00,  350.00,  280, 30, 0.800, 1),
-- 食品
(18, 'FD-001',  '五常大米10kg装',           10, 5, 89.90,  55.00,    1000,100, 10.000, 1),
(19, 'FD-002',  '金龙鱼花生油5L',           10, 5, 129.90, 85.00,    800, 80, 4.600, 1),
-- 生鲜
(20, 'FR-001',  '阿克苏冰糖心苹果5kg',      11, 5, 59.90,  38.00,    600, 50, 5.000, 1),
(21, 'FR-002',  '智利进口车厘子2kg',        11, 5, 199.00, 130.00,   200, 20, 2.000, 1),
-- 零食
(22, 'SN-001',  '三只松鼠坚果礼盒',         12, 5, 128.00, 75.00,    400, 40, 1.500, 1),
(23, 'SN-002',  '良品铺子猪肉脯500g',       12, 5, 49.90,  28.00,    600, 50, 0.500, 1),
(24, 'SN-003',  '百草味每日坚果30袋装',     12, 5, 99.00,  60.00,    350, 30, 1.200, 1);

-- ============================================================
-- 4. 客户
-- ============================================================
CREATE TABLE IF NOT EXISTS customers (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL COMMENT '客户姓名',
    gender          TINYINT COMMENT '1=男 2=女 0=未知',
    phone           VARCHAR(50)  COMMENT '手机号',
    email           VARCHAR(200) COMMENT '邮箱',
    province        VARCHAR(50)  COMMENT '省份',
    city            VARCHAR(50)  COMMENT '城市',
    level           TINYINT DEFAULT 1 COMMENT '会员等级 1-5',
    total_spent     DECIMAL(12,2) DEFAULT 0 COMMENT '累计消费金额',
    total_orders    INT DEFAULT 0 COMMENT '累计下单次数',
    register_date   DATE COMMENT '注册日期',
    last_login      DATETIME COMMENT '最后登录',
    status          TINYINT DEFAULT 1 COMMENT '1=活跃 0=冻结',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_level (level),
    INDEX idx_province (province),
    INDEX idx_total_spent (total_spent)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户';

INSERT INTO customers (id, name, gender, phone, email, province, city, level, total_spent, total_orders, register_date, last_login, status) VALUES
(1,  '王明',  1, '13800138001', 'wangming@email.com',   '广东', '深圳', 5, 52800.00, 12, '2023-03-15', '2026-06-20 10:30:00', 1),
(2,  '李芳',  2, '13900139002', 'lifang@email.com',     '北京', '北京', 4, 32300.00, 8,  '2023-05-20', '2026-06-18 14:20:00', 1),
(3,  '张强',  1, '13700137003', 'zhangqiang@email.com', '上海', '上海', 5, 78900.00, 15, '2022-11-01', '2026-06-22 09:15:00', 1),
(4,  '刘娟',  2, '13600136004', 'liujuan@email.com',    '浙江', '杭州', 3, 12500.00, 5,  '2024-01-10', '2026-05-30 16:45:00', 1),
(5,  '赵勇',  1, '13500135005', 'zhaoyong@email.com',   '广东', '广州', 4, 45600.00, 10, '2023-08-05', '2026-06-15 11:00:00', 1),
(6,  '陈丽',  2, '13400134006', 'chenli@email.com',      '四川', '成都', 2, 3800.00,  3,  '2024-06-01', '2026-04-12 08:30:00', 1),
(7,  '杨刚',  1, '13300133007', 'yanggang@email.com',    '湖北', '武汉', 4, 28900.00, 7,  '2023-10-18', '2026-06-10 13:50:00', 1),
(8,  '周婷',  2, '13200132008', 'zhouting@email.com',    '江苏', '南京', 3, 15900.00, 6,  '2024-02-28', '2026-05-25 10:10:00', 1),
(9,  '黄伟',  1, '13100131009', 'huangwei@email.com',    '山东', '青岛', 1, 1200.00,  2,  '2024-09-12', '2026-03-01 09:00:00', 1),
(10, '吴静',  2, '13000130010', 'wujing@email.com',      '福建', '厦门', 3, 22300.00, 5,  '2023-12-03', '2026-06-08 15:30:00', 1),
(11, '孙凯',  1, '12900129011', 'sunkai@email.com',      '湖南', '长沙', 2, 6800.00,  4,  '2024-04-20', '2026-05-10 12:00:00', 1),
(12, '马丽',  2, '12800128012', 'mali@email.com',        '河南', '郑州', 4, 35100.00, 9,  '2023-07-28', '2026-06-01 17:00:00', 1),
(13, '林涛',  1, '12700127013', 'lintao@email.com',      '广东', '东莞', 5, 62300.00, 11, '2023-02-14', '2026-06-21 20:30:00', 1),
(14, '何燕',  2, '12600126014', 'heyan@email.com',        '江苏', '苏州', 1, 800.00,   1,  '2025-01-05', '2026-02-28 07:00:00', 0),
(15, '罗斌',  1, '12500125015', 'luobin@email.com',       '四川', '绵阳', 2, 5200.00,  3,  '2024-07-15', '2026-04-20 10:00:00', 1),
(16, '客户_测试', 0, NULL, NULL, '测试省', '测试市', 1, 0.00, 0, '2025-06-01', NULL, 1);

-- ============================================================
-- 5. 部门
-- ============================================================
CREATE TABLE IF NOT EXISTS departments (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL COMMENT '部门名称',
    manager_id  BIGINT DEFAULT NULL COMMENT '部门负责人(员工ID)',
    budget      DECIMAL(14,2) DEFAULT 0 COMMENT '部门年度预算',
    status      TINYINT DEFAULT 1,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门';

INSERT INTO departments (id, name, manager_id, budget, status) VALUES
(1, '技术部',     1, 5000000.00, 1),
(2, '销售部',     5, 8000000.00, 1),
(3, '市场部',     8, 3000000.00, 1),
(4, '财务部',     10, 2000000.00, 1),
(5, '人力资源部', 12, 1500000.00, 1),
(6, '供应链部',   3, 4000000.00, 1);

-- ============================================================
-- 6. 员工（含敏感字段：薪资）
-- ============================================================
CREATE TABLE IF NOT EXISTS employees (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    emp_no          VARCHAR(20) NOT NULL UNIQUE COMMENT '工号',
    name            VARCHAR(100) NOT NULL COMMENT '姓名',
    gender          TINYINT COMMENT '1=男 2=女',
    phone           VARCHAR(50),
    email           VARCHAR(200),
    department_id   BIGINT NOT NULL,
    position        VARCHAR(100) COMMENT '职位',
    salary_base     DECIMAL(10,2) NOT NULL COMMENT '基本月薪',
    salary_bonus    DECIMAL(10,2) DEFAULT 0 COMMENT '月度奖金',
    hire_date       DATE COMMENT '入职日期',
    status          TINYINT DEFAULT 1 COMMENT '1=在职 0=离职',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_dept (department_id),
    INDEX idx_hire (hire_date),
    FOREIGN KEY (department_id) REFERENCES departments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工（含薪酬敏感字段）';

INSERT INTO employees (id, emp_no, name, gender, phone, email, department_id, position, salary_base, salary_bonus, hire_date, status) VALUES
(1,  'EMP-001', '刘建国', 1, '13811110001', 'liujianguo@example.com',  1, '技术总监',    45000.00, 10000.00, '2019-03-01', 1),
(2,  'EMP-002', '王小红', 2, '13811110002', 'wangxiaohong@example.com', 1, '高级Java工程师', 32000.00, 6000.00, '2020-06-15', 1),
(3,  'EMP-003', '张明辉', 1, '13811110003', 'zhangminghui@example.com', 1, '前端工程师',   22000.00, 4000.00, '2021-09-01', 1),
(4,  'EMP-004', '赵丽华', 2, '13811110004', 'zhaolihua@example.com',   1, '测试工程师',   18000.00, 3000.00, '2022-03-15', 1),
(5,  'EMP-005', '陈德强', 1, '13811110005', 'chendeqiang@example.com',  2, '销售总监',    50000.00, 20000.00, '2018-07-01', 1),
(6,  'EMP-006', '李思雨', 2, '13811110006', 'lisiyu@example.com',       2, '销售经理',    28000.00, 10000.00, '2020-01-10', 1),
(7,  'EMP-007', '周文斌', 1, '13811110007', 'zhouwenbin@example.com',   2, '销售代表',    15000.00, 8000.00, '2023-04-01', 1),
(8,  'EMP-008', '吴美玲', 2, '13811110008', 'wumeiling@example.com',    3, '市场总监',    42000.00, 8000.00, '2019-11-20', 1),
(9,  'EMP-009', '郑阳',   1, '13811110009', 'zhengyang@example.com',   3, '品牌运营',    18000.00, 3000.00, '2022-08-01', 1),
(10, 'EMP-010', '黄丽娟', 2, '13811110010', 'huanglijuan@example.com',  4, '财务总监',    40000.00, 8000.00, '2020-04-15', 1),
(11, 'EMP-011', '唐晓峰', 1, '13811110011', 'tangxiaofeng@example.com', 4, '会计',        16000.00, 2000.00, '2023-06-01', 1),
(12, 'EMP-012', '韩梅',   2, '13811110012', 'hanmei@example.com',       5, 'HR总监',      38000.00, 6000.00, '2019-08-01', 1),
(13, 'EMP-013', '冯志强', 1, '13811110013', 'fengzhiqiang@example.com', 5, '招聘主管',    18000.00, 3000.00, '2021-12-01', 1),
(14, 'EMP-014', '曹雪琴', 2, '13811110014', 'caoxueqin@example.com',    6, '供应链总监',  42000.00, 8000.00, '2020-02-10', 1),
(15, 'EMP-015', '邓志强', 1, '13811110015', 'dengzhiqiang@example.com', 6, '仓储经理',    20000.00, 4000.00, '2021-05-15', 1);

-- ============================================================
-- 7. 订单
-- ============================================================
CREATE TABLE IF NOT EXISTS orders (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(50) NOT NULL UNIQUE COMMENT '订单号',
    customer_id     BIGINT NOT NULL,
    total_amount    DECIMAL(12,2) NOT NULL COMMENT '订单总金额',
    discount_amount DECIMAL(10,2) DEFAULT 0 COMMENT '优惠金额',
    payment_method  VARCHAR(30) COMMENT '支付方式: wechat/alipay/card',
    order_status    VARCHAR(30) NOT NULL COMMENT 'pending/paid/shipped/delivered/cancelled/refunded',
    paid_at         DATETIME COMMENT '支付时间',
    shipped_at      DATETIME COMMENT '发货时间',
    delivered_at    DATETIME COMMENT '签收时间',
    shipping_fee    DECIMAL(8,2) DEFAULT 0 COMMENT '运费',
    receiver_name   VARCHAR(100) COMMENT '收货人',
    receiver_phone  VARCHAR(50) COMMENT '收货电话',
    receiver_addr   VARCHAR(500) COMMENT '收货地址',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_customer (customer_id),
    INDEX idx_status (order_status),
    INDEX idx_paid (paid_at),
    INDEX idx_created (created_at),
    FOREIGN KEY (customer_id) REFERENCES customers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

INSERT INTO orders (id, order_no, customer_id, total_amount, discount_amount, payment_method, order_status, paid_at, shipped_at, delivered_at, shipping_fee, receiver_name, receiver_phone, receiver_addr, created_at) VALUES
-- 2025年订单
(1,  'ORD-202501001', 1,  15998.00, 200.00,  'wechat',  'delivered', '2025-01-05 10:30:00', '2025-01-06 14:00:00', '2025-01-08 09:00:00', 0,   '王明', '13800138001', '广东深圳南山区科技园',   '2025-01-05 10:15:00'),
(2,  'ORD-202501002', 3,  8999.00,  100.00,  'alipay',  'delivered', '2025-01-12 14:20:00', '2025-01-13 10:00:00', '2025-01-15 16:00:00', 15.00, '张强', '13700137003', '上海浦东新区陆家嘴',     '2025-01-12 14:00:00'),
(3,  'ORD-202502001', 5,  7299.00,  0.00,    'wechat',  'delivered', '2025-02-18 09:45:00', '2025-02-19 11:00:00', '2025-02-21 10:30:00', 20.00, '赵勇', '13500135005', '广东广州天河区体育西路', '2025-02-18 09:30:00'),
(4,  'ORD-202503001', 12, 2599.00,  50.00,   'card',    'delivered', '2025-03-08 16:10:00', '2025-03-09 08:00:00', '2025-03-11 14:00:00', 0,   '马丽', '12800128012', '河南郑州金水区',         '2025-03-08 15:50:00'),
(5,  'ORD-202503002', 7,  14999.00, 500.00,  'alipay',  'delivered', '2025-03-20 11:00:00', '2025-03-21 09:00:00', '2025-03-23 12:00:00', 30.00, '杨刚', '13300133007', '湖北武汉洪山区',         '2025-03-20 10:30:00'),
(6,  'ORD-202504001', 2,  4599.00,  0.00,    'wechat',  'delivered', '2025-04-05 08:30:00', '2025-04-06 14:00:00', '2025-04-08 10:00:00', 0,   '李芳', '13900139002', '北京朝阳区望京',         '2025-04-05 08:15:00'),
(7,  'ORD-202505001', 10, 6899.00,  100.00,  'alipay',  'delivered', '2025-05-12 15:00:00', '2025-05-13 10:00:00', '2025-05-15 13:00:00', 10.00, '吴静', '13000130010', '福建厦门思明区',         '2025-05-12 14:30:00'),
(8,  'ORD-202506001', 1,  10999.00, 300.00,  'wechat',  'delivered', '2025-06-01 20:00:00', '2025-06-02 09:00:00', '2025-06-04 11:00:00', 0,   '王明', '13800138001', '广东深圳南山区科技园',   '2025-06-01 19:45:00'),
(9,  'ORD-202507001', 13, 7999.00,  0.00,    'card',    'delivered', '2025-07-22 13:30:00', '2025-07-23 08:00:00', '2025-07-25 16:00:00', 25.00, '林涛', '12700127013', '广东东莞长安镇',         '2025-07-22 13:15:00'),
(10, 'ORD-202508001', 3,  18999.00, 800.00,  'alipay',  'delivered', '2025-08-08 10:00:00', '2025-08-09 14:00:00', '2025-08-11 09:00:00', 0,   '张强', '13700137003', '上海浦东新区陆家嘴',     '2025-08-08 09:30:00'),
(11, 'ORD-202509001', 5,  3499.00,  0.00,    'wechat',  'delivered', '2025-09-15 17:20:00', '2025-09-16 10:00:00', '2025-09-18 14:30:00', 0,   '赵勇', '13500135005', '广东广州天河区体育西路', '2025-09-15 17:00:00'),
(12, 'ORD-202510001', 12, 7999.00,  200.00,  'alipay',  'delivered', '2025-10-01 09:00:00', '2025-10-02 08:00:00', '2025-10-04 10:00:00', 15.00, '马丽', '12800128012', '河南郑州金水区',         '2025-10-01 08:45:00'),
(13, 'ORD-202511001', 2,  12999.00, 300.00,  'card',    'delivered', '2025-11-11 00:05:00', '2025-11-11 14:00:00', '2025-11-13 16:00:00', 0,   '李芳', '13900139002', '北京朝阳区望京',         '2025-11-11 00:00:00'),
(14, 'ORD-202512001', 1,  7599.00,  100.00,  'wechat',  'delivered', '2025-12-25 08:30:00', '2025-12-26 10:00:00', '2025-12-28 13:00:00', 0,   '王明', '13800138001', '广东深圳南山区科技园',   '2025-12-25 08:15:00'),
(15, 'ORD-202512002', 7,  4999.00,  0.00,    'alipay',  'delivered', '2025-12-30 14:00:00', '2025-12-31 09:00:00', '2026-01-02 10:00:00', 20.00, '杨刚', '13300133007', '湖北武汉洪山区',         '2025-12-30 13:30:00'),
-- 2026年订单
(16, 'ORD-202601001', 3,  22998.00, 1000.00, 'wechat',  'delivered', '2026-01-15 10:00:00', '2026-01-16 09:00:00', '2026-01-18 11:00:00', 0,   '张强', '13700137003', '上海浦东新区陆家嘴',     '2026-01-15 09:30:00'),
(17, 'ORD-202602001', 13, 5999.00,  0.00,    'alipay',  'delivered', '2026-02-14 11:30:00', '2026-02-15 08:00:00', '2026-02-17 14:00:00', 10.00, '林涛', '12700127013', '广东东莞长安镇',         '2026-02-14 11:00:00'),
(18, 'ORD-202603001', 5,  8999.00,  200.00,  'wechat',  'delivered', '2026-03-08 15:00:00', '2026-03-09 10:00:00', '2026-03-11 09:00:00', 0,   '赵勇', '13500135005', '广东广州天河区体育西路', '2026-03-08 14:30:00'),
(19, 'ORD-202603002', 8,  4599.00,  50.00,   'card',    'delivered', '2026-03-20 09:30:00', '2026-03-21 14:00:00', '2026-03-23 16:00:00', 0,   '周婷', '13200132008', '江苏南京鼓楼区',         '2026-03-20 09:15:00'),
(20, 'ORD-202604001', 1,  12998.00, 500.00,  'alipay',  'shipped',   '2026-04-10 10:30:00', '2026-04-11 14:00:00', NULL,               0,   '王明', '13800138001', '广东深圳南山区科技园',   '2026-04-10 10:00:00'),
(21, 'ORD-202604002', 10, 2999.00,  0.00,    'wechat',  'paid',      '2026-04-18 16:20:00', NULL,                NULL,               15.00, '吴静', '13000130010', '福建厦门思明区',         '2026-04-18 15:50:00'),
(22, 'ORD-202605001', 12, 15999.00, 300.00,  'alipay',  'paid',      '2026-05-01 08:00:00', NULL,                NULL,               0,   '马丽', '12800128012', '河南郑州金水区',         '2026-05-01 07:45:00'),
(23, 'ORD-202605002', 6,  2599.00,  0.00,    'card',    'pending',   NULL,                   NULL,                NULL,               20.00, '陈丽', '13400134006', '四川成都高新区',         '2026-05-20 18:30:00'),
(24, 'ORD-202606001', 3,  18999.00, 500.00,  'wechat',  'paid',      '2026-06-05 11:00:00', NULL,                NULL,               0,   '张强', '13700137003', '上海浦东新区陆家嘴',     '2026-06-05 10:30:00'),
(25, 'ORD-202606002', 15, 4599.00,  100.00,  'alipay',  'pending',   NULL,                   NULL,                NULL,               0,   '罗斌', '12500125015', '四川绵阳游仙区',         '2026-06-15 20:00:00'),
-- 已取消订单
(26, 'ORD-202506101', 4,  5999.00,  0.00,    'alipay',  'cancelled', NULL, NULL, NULL, 0, '刘娟', '13600136004', '浙江杭州西湖区', '2025-06-10 09:00:00'),
(27, 'ORD-202509201', 6,  1999.00,  0.00,    'wechat',  'refunded', '2025-09-20 10:00:00', '2025-09-21 08:00:00', NULL, 10.00, '陈丽', '13400134006', '四川成都高新区', '2025-09-20 09:30:00'),
-- 大客户更多订单
(28, 'ORD-202601201', 1,  19999.00, 1000.00, 'alipay',  'delivered', '2026-01-20 14:00:00', '2026-01-21 10:00:00', '2026-01-23 15:00:00', 0, '王明', '13800138001', '广东深圳南山区科技园', '2026-01-20 13:30:00'),
(29, 'ORD-202602202', 3,  8999.00,  200.00,  'wechat',  'delivered', '2026-02-22 10:30:00', '2026-02-23 09:00:00', '2026-02-25 12:00:00', 0, '张强', '13700137003', '上海浦东新区陆家嘴',   '2026-02-22 10:00:00'),
(30, 'ORD-202604301', 3,  33999.00, 2000.00, 'card',    'paid',      '2026-04-30 09:15:00', NULL,                NULL,               0, '张强', '13700137003', '上海浦东新区陆家嘴',   '2026-04-30 08:45:00');

-- ============================================================
-- 8. 订单明细
-- ============================================================
CREATE TABLE IF NOT EXISTS order_items (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT NOT NULL,
    product_id      BIGINT NOT NULL,
    product_name    VARCHAR(200) NOT NULL COMMENT '下单时商品名(快照)',
    quantity        INT NOT NULL COMMENT '数量',
    unit_price      DECIMAL(10,2) NOT NULL COMMENT '成交单价',
    subtotal        DECIMAL(12,2) NOT NULL COMMENT '小计',
    INDEX idx_order (order_id),
    INDEX idx_product (product_id),
    FOREIGN KEY (order_id) REFERENCES orders(id),
    FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

INSERT INTO order_items (id, order_id, product_id, product_name, quantity, unit_price, subtotal) VALUES
-- ORD-202501001 (手机 + 电脑)
(1,   1,  1, '华为Mate 60 Pro 512G',     1, 7999.00, 7999.00),
(2,   1,  5, '联想ThinkPad X1 Carbon',   1, 7999.00, 7999.00), -- 非首发价
-- ORD-202501002 (手机)
(3,   2,  2, 'iPhone 15 Pro Max 256G',   1, 8999.00, 8999.00),
-- ORD-202502001 (空调+吸尘器)
(4,   3,  8, '海尔3匹变频冷暖空调',      1, 5999.00, 5999.00),
(5,   3,  11,'戴森V15吸尘器',            1, 1300.00, 1300.00), -- 折扣
-- ORD-202503001 (衣服)
(6,   4,  15,'七匹狼纯棉T恤',            2, 199.00,  398.00),
(7,   4,  16,'ONLY春秋连衣裙',           1, 459.00,  459.00),
(8,   4,  14,'海澜之家商务衬衫',         3, 299.00,  897.00),
-- ORD-202503002 (手机+电脑)
(9,   5,  6, '苹果MacBook Pro 14寸 M3',  1, 14999.00, 14999.00),
-- ORD-202504001 (空调)
(10,  6,  10,'格力1.5匹壁挂式空调',      1, 3299.00, 3299.00),
(11,  6,  12,'九阳破壁豆浆机',           1, 599.00,  599.00),
-- ORD-202505001 (手机+食品)
(12,  7,  3, '小米14 Ultra',             1, 5999.00, 5999.00),
(13,  7,  18,'五常大米10kg装',           2, 89.90,   179.80),
(14,  7,  19,'金龙鱼花生油5L',           1, 129.90,  129.90),
-- ORD-202506001 (手机+电脑)
(15,  8,  1, '华为Mate 60 Pro 512G',     1, 7999.00, 7999.00),
(16,  8,  6, '苹果MacBook Pro 14寸',     1, 3000.00, 3000.00), -- 折扣价(非原价)
-- ORD-202507001 (手机)
(17,  9,  2, 'iPhone 15 Pro Max 256G',   1, 7999.00, 7999.00),
-- ORD-202508001 (手机+电脑+空调)
(18,  10, 2, 'iPhone 15 Pro Max 256G',   1, 9999.00, 9999.00),
(19,  10, 7, '戴尔XPS 16 2024款',       1, 6000.00, 6000.00), -- 折扣
-- ORD-202509001 (衣服)
(20,  11, 14,'海澜之家商务衬衫',         5, 299.00,  1495.00),
(21,  11, 15,'七匹狼纯棉T恤',            3, 199.00,  597.00),
(22,  11, 17,'优衣库女装羽绒服',         1, 799.00,  799.00),
-- ORD-202510001 (家电)
(23,  12, 9, '美的对开门冰箱600L',       1, 4999.00, 4999.00),
(24,  12, 13,'小米空气净化器4 Pro',      1, 1999.00, 1999.00),
-- ORD-202511001 (电脑)
(25,  13, 6, '苹果MacBook Pro 14寸 M3',  1, 12999.00,12999.00),
-- ORD-202512001 (手机)
(26,  14, 3, '小米14 Ultra',             1, 5999.00, 5999.00),
(27,  14, 20,'阿克苏冰糖心苹果5kg',      1, 59.90,   59.90),
-- ORD-202512002 (衣服)
(28,  15, 17,'优衣库女装羽绒服',         2, 799.00,  1598.00),
(29,  15, 15,'七匹狼纯棉T恤',            2, 199.00,  398.00),
-- 2026
(30,  16, 5, '联想ThinkPad X1 Carbon',   1, 12999.00,12999.00),
(31,  16, 1, '华为Mate 60 Pro 512G',     1, 7999.00, 7999.00),
(32,  17, 3, '小米14 Ultra',             1, 5999.00, 5999.00),
(33,  18, 8, '海尔3匹变频冷暖空调',      1, 5999.00, 5999.00),
(34,  18, 11,'戴森V15吸尘器',            1, 2999.00, 2999.00), -- 恢复原价
(35,  19, 12,'九阳破壁豆浆机',           2, 599.00,  1198.00),
(36,  19, 20,'阿克苏冰糖心苹果5kg',      2, 59.90,   119.80),
(37,  20, 5, '联想ThinkPad X1 Carbon',   1, 12999.00,12999.00),
(38,  21, 11,'戴森V15吸尘器',            1, 2999.00, 2999.00),
(39,  22, 6, '苹果MacBook Pro 14寸 M3',  1, 14999.00,14999.00),
(40,  22, 19,'金龙鱼花生油5L',           2, 129.90,  259.80),
(41,  23, 15,'七匹狼纯棉T恤',            2, 199.00,  398.00),
(42,  23, 18,'五常大米10kg装',           1, 89.90,   89.90),
(43,  24, 2, 'iPhone 15 Pro Max 256G',   1, 9999.00, 9999.00),
(44,  24, 8, '海尔3匹变频冷暖空调',      1, 5999.00, 5999.00),
(45,  25, 13,'小米空气净化器4 Pro',      1, 1999.00, 1999.00),
(46,  25, 20,'阿克苏冰糖心苹果5kg',      1, 59.90,   59.90),
-- 已取消/已退款的订单
(47,  26, 3, '小米14 Ultra',             1, 5999.00, 5999.00),
(48,  27, 12,'九阳破壁豆浆机',           2, 599.00,  1198.00),
-- 大客户补充
(49,  28, 7, '戴尔XPS 16 2024款',       1, 13999.00,13999.00),
(50,  28, 2, 'iPhone 15 Pro Max 256G',   1, 5999.00, 5999.00), -- 员工内购价
(51,  29, 10,'格力1.5匹壁挂式空调',      1, 3299.00, 3299.00),
(52,  29, 18,'五常大米10kg装',           3, 89.90,   269.70),
(53,  30, 6, '苹果MacBook Pro 14寸 M3',  2, 14999.00,29998.00),
(54,  30, 11,'戴森V15吸尘器',            1, 4990.00, 4990.00);

-- ============================================================
-- 9. 库存流水
-- ============================================================
CREATE TABLE IF NOT EXISTS inventory_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id  BIGINT NOT NULL,
    change_type VARCHAR(30) NOT NULL COMMENT 'inbound/outbound/return/adjustment',
    quantity    INT NOT NULL COMMENT '正=入库 负=出库',
    before_qty  INT NOT NULL COMMENT '变更前库存',
    after_qty   INT NOT NULL COMMENT '变更后库存',
    ref_no      VARCHAR(100) COMMENT '关联单号(采购单/订单)',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_product (product_id),
    INDEX idx_type (change_type),
    INDEX idx_created (created_at),
    FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水';

INSERT INTO inventory_log (id, product_id, change_type, quantity, before_qty, after_qty, ref_no, created_at) VALUES
(1,  1,  'inbound',   200,  0,   200,  'PO-20240101', '2024-01-01 10:00:00'),
(2,  1,  'outbound',  -5,   200, 195,  'ORD-202501001','2025-01-06 14:00:00'),
(3,  1,  'outbound',  -1,   195, 194,  'ORD-202506001','2025-06-02 09:00:00'),
(4,  1,  'outbound',  -1,   194, 193,  'ORD-202601201','2026-01-21 10:00:00'),
(5,  1,  'outbound',  -1,   193, 192,  'ORD-202606001','2026-06-06 10:00:00'),
(6,  5,  'inbound',   100,  0,   100,  'PO-20240102', '2024-01-05 10:00:00'),
(7,  5,  'outbound',  -1,   100, 99,   'ORD-202501001','2025-01-06 14:00:00'),
(8,  5,  'outbound',  -1,   99,  98,   'ORD-202507001','2025-07-06 14:00:00'),
(9,  5,  'outbound',  -1,   98,  97,   'ORD-202603001','2026-03-09 10:00:00'),
(10, 2,  'inbound',   150,  0,   150,  'PO-20240103', '2024-01-10 10:00:00'),
(11, 2,  'outbound',  -1,   150, 149,  'ORD-202501002','2025-01-13 10:00:00'),
(12, 2,  'outbound',  -1,   149, 148,  'ORD-202507001','2025-07-22 14:00:00'),
(13, 2,  'outbound',  -1,   148, 147,  'ORD-202508001','2025-08-09 14:00:00'),
(14, 2,  'outbound',  -1,   147, 146,  'ORD-202606001','2026-06-06 10:00:00'),
(15, 6,  'inbound',   60,   0,   60,   'PO-20240104', '2024-02-01 10:00:00'),
(16, 6,  'outbound',  -1,   60,  59,   'ORD-202503002','2025-03-21 09:00:00'),
(17, 6,  'outbound',  -1,   59,  58,   'ORD-202505001','2025-05-13 10:00:00'),
(18, 6,  'outbound',  -1,   58,  57,   'ORD-202511001','2025-11-11 14:00:00'),
(19, 6,  'outbound',  -1,   57,  56,   'ORD-202601201','2026-01-21 10:00:00'),
(20, 6,  'outbound',  -2,   56,  54,   'ORD-202604301','2026-04-30 10:00:00'),
(21, 8,  'inbound',   100,  0,   100,  'PO-20240201', '2024-02-10 10:00:00'),
(22, 8,  'outbound',  -1,   100, 99,   'ORD-202502001','2025-02-19 11:00:00'),
(23, 8,  'outbound',  -1,   99,  98,   'ORD-202603001','2026-03-09 10:00:00'),
(24, 8,  'outbound',  -1,   98,  97,   'ORD-202606001','2026-06-06 10:00:00'),
(25, 3,  'inbound',   300,  0,   300,  'PO-20240301', '2024-03-01 10:00:00'),
(26, 3,  'outbound',  -1,   300, 299,  'ORD-202505001','2025-05-13 10:00:00'),
(27, 3,  'outbound',  -1,   299, 298,  'ORD-202512001','2025-12-26 10:00:00'),
(28, 3,  'outbound',  -1,   298, 297,  'ORD-202606001','2026-06-06 10:00:00');

-- 更新累计统计
UPDATE customers c
SET total_spent = (SELECT COALESCE(SUM(o.total_amount - o.discount_amount), 0) FROM orders o WHERE o.customer_id = c.id AND o.order_status IN ('paid','shipped','delivered')),
    total_orders = (SELECT COUNT(*) FROM orders o WHERE o.customer_id = c.id AND o.order_status IN ('paid','shipped','delivered'));

-- 更新分类路径（父分类名）
UPDATE categories c1
LEFT JOIN categories c2 ON c1.parent_id = c2.id
SET c1.category_name = c2.category_name;

-- ============================================================
-- 验证
-- ============================================================
SELECT 'Orders' AS tbl, COUNT(*) AS cnt FROM orders
UNION ALL SELECT 'Order Items', COUNT(*) FROM order_items
UNION ALL SELECT 'Products', COUNT(*) FROM products
UNION ALL SELECT 'Customers', COUNT(*) FROM customers
UNION ALL SELECT 'Employees', COUNT(*) FROM employees
UNION ALL SELECT 'Departments', COUNT(*) FROM departments
UNION ALL SELECT 'Suppliers', COUNT(*) FROM suppliers
UNION ALL SELECT 'Categories', COUNT(*) FROM categories
UNION ALL SELECT 'Inventory Log', COUNT(*) FROM inventory_log;
