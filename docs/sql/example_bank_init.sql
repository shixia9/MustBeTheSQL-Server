-- ============================================================
-- Example Test Database for SQL Agent — 金融/银行领域
-- 包含 9 张表、按时间分布的交易记录、贷款/风控敏感数据
-- 与 example_init.sql（电商）互补，覆盖不同分析模式
-- ============================================================

CREATE DATABASE IF NOT EXISTS example_bank;
USE example_bank;

-- ============================================================
-- 1. 银行网点
-- ============================================================
CREATE TABLE IF NOT EXISTS branches (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(20)  NOT NULL UNIQUE COMMENT '网点编号',
    name        VARCHAR(100) NOT NULL COMMENT '网点名称',
    province    VARCHAR(50)  NOT NULL COMMENT '省份',
    city        VARCHAR(50)  NOT NULL COMMENT '城市',
    district    VARCHAR(50)  COMMENT '区域',
    address     VARCHAR(200) COMMENT '详细地址',
    phone       VARCHAR(50)  COMMENT '联系电话',
    open_date   DATE         COMMENT '开业日期',
    assets      DECIMAL(16,2) DEFAULT 0 COMMENT '网点管理资产(元)',
    emp_count   INT DEFAULT 0 COMMENT '员工人数',
    status      TINYINT DEFAULT 1 COMMENT '1=营业 0=停业',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_province (province),
    INDEX idx_assets (assets)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='银行网点';

INSERT INTO branches (id, code, name, province, city, district, address, phone, open_date, assets, emp_count, status) VALUES
(1, 'SZNS',  '深圳南山支行',       '广东', '深圳', '南山区',     '南山区科技南路100号',    '0755-86010001', '2010-03-15', 2850000000.00, 28, 1),
(2, 'SZBJ',  '深圳宝安支行',       '广东', '深圳', '宝安区',     '宝安中心区创业路200号',  '0755-86010002', '2012-07-20', 1560000000.00, 22, 1),
(3, 'GZTY',  '广州天河北支行',     '广东', '广州', '天河北路',   '天河北路500号',          '020-38880001', '2011-05-10', 3200000000.00, 35, 1),
(4, 'SHPUD', '上海浦东支行',       '上海', '上海', '浦东新区',   '陆家嘴金融中心100号',    '021-68880001', '2008-01-08', 5200000000.00, 45, 1),
(5, 'BJCD',  '北京朝阳支行',       '北京', '北京', '朝阳区',     '建国路88号国贸大厦',     '010-58880001', '2009-09-01', 4800000000.00, 40, 1),
(6, 'HZWL',  '杭州武林支行',       '浙江', '杭州', '下城区',     '武林广场商业中心',       '0571-87780001', '2013-11-18', 1950000000.00, 20, 1),
(7, 'CDGX',  '成都高新支行',       '四川', '成都', '高新区',     '天府大道南段500号',      '028-85580001', '2014-06-01', 1200000000.00, 18, 1),
(8, 'WHGS',  '武汉光谷支行',       '湖北', '武汉', '东湖高新区', '光谷大道100号',         '027-87780001', '2015-03-25', 980000000.00,  15, 1),
(9, 'NJJL',  '南京江宁支行',       '江苏', '南京', '江宁区',     '双龙大道1000号',        '025-86680001', '2016-08-12', 650000000.00,  12, 1),
(10, 'CQJN', '重庆江南支行',       '重庆', '重庆', '南岸区',     '南滨路200号',            '023-68880001', '2017-01-05', 780000000.00,  14, 1),
(11, 'SYHP', '沈阳和平支行',       '辽宁', '沈阳', '和平区',     '中华路300号',            '024-82280001', '2018-10-20', 420000000.00,  10, 1);

-- ============================================================
-- 2. 客户（银行对公+对私）
-- ============================================================
CREATE TABLE IF NOT EXISTS bank_customers (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    id_type         VARCHAR(20) COMMENT '证件类型: ID_CARD/PASSPORT/BUSINESS_LICENSE',
    id_number       VARCHAR(50) COMMENT '证件号(masked)',
    name            VARCHAR(100) NOT NULL COMMENT '客户名称',
    type            VARCHAR(20) NOT NULL COMMENT 'PERSONAL/COMPANY',
    gender          TINYINT COMMENT '1=男 2=女(个人)',
    phone           VARCHAR(50) COMMENT '联系电话',
    email           VARCHAR(200) COMMENT '邮箱',
    province        VARCHAR(50) COMMENT '省份',
    city            VARCHAR(50) COMMENT '城市',
    occupation      VARCHAR(100) COMMENT '职业(个人)',
    industry        VARCHAR(100) COMMENT '行业(对公)',
    risk_level      VARCHAR(20) DEFAULT 'LOW' COMMENT '风险等级: LOW/MEDIUM/HIGH',
    kyc_status      TINYINT DEFAULT 1 COMMENT '1=已认证 0=待认证',
    register_date   DATE COMMENT '开户日期',
    total_assets    DECIMAL(14,2) DEFAULT 0 COMMENT '总资产(元)',
    status          TINYINT DEFAULT 1 COMMENT '1=正常 0=冻结',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_type (type),
    INDEX idx_province (province),
    INDEX idx_risk (risk_level),
    INDEX idx_assets (total_assets)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='银行客户';

INSERT INTO bank_customers (id, id_type, id_number, name, type, gender, phone, email, province, city, occupation, industry, risk_level, kyc_status, register_date, total_assets, status) VALUES
-- 个人客户
(1,  'ID_CARD', '440301****0011', '陈建国',   'PERSONAL', 1, '13910010001', 'chenjg@email.com',    '广东', '深圳', 'IT工程师',        NULL,         'LOW',   1, '2018-03-01', 1850000.00,  1),
(2,  'ID_CARD', '440301****0022', '李雪梅',   'PERSONAL', 2, '13910010002', 'lixm@email.com',      '广东', '广州', '企业高管',        NULL,         'LOW',   1, '2019-06-15', 3200000.00,  1),
(3,  'ID_CARD', '310101****0033', '张伟明',   'PERSONAL', 1, '13910010003', 'zhangwm@email.com',   '上海', '上海', '金融从业者',      NULL,         'MEDIUM',1, '2017-11-01', 5800000.00,  1),
(4,  'ID_CARD', '110101****0044', '王淑芳',   'PERSONAL', 2, '13910010004', 'wangsf@email.com',    '北京', '北京', '医生',            NULL,         'LOW',   1, '2020-01-20', 1250000.00,  1),
(5,  'ID_CARD', '510101****0055', '刘志强',   'PERSONAL', 1, '13910010005', 'liuzq@email.com',     '四川', '成都', '个体商户',        NULL,         'MEDIUM',1, '2019-09-01', 890000.00,   1),
(6,  'ID_CARD', '420101****0066', '赵文婷',   'PERSONAL', 2, '13910010006', 'zhaowt@email.com',    '湖北', '武汉', '教师',            NULL,         'LOW',   1, '2021-04-10', 420000.00,   1),
(7,  'ID_CARD', '320101****0077', '孙浩杰',   'PERSONAL', 1, '13910010007', 'sunhj@email.com',     '江苏', '南京', '公务员',          NULL,         'LOW',   1, '2020-08-05', 680000.00,   1),
(8,  'ID_CARD', '330101****0088', '周美玲',   'PERSONAL', 2, '13910010008', 'zhouml@email.com',    '浙江', '杭州', '电商运营',        NULL,         'LOW',   1, '2021-12-18', 960000.00,   1),
(9,  'ID_CARD', '500101****0099', '黄建华',   'PERSONAL', 1, '13910010009', 'huangjh@email.com',   '重庆', '重庆', '律师',            NULL,         'MEDIUM',1, '2022-03-01', 2100000.00,  1),
(10, 'ID_CARD', '210101****0100', '林晓燕',   'PERSONAL', 2, '13910010010', 'linxy@email.com',     '辽宁', '沈阳', '会计',            NULL,         'LOW',   1, '2022-07-15', 350000.00,   1),
(11, 'ID_CARD', '440301****0111', '吴俊杰',   'PERSONAL', 1, '13910010011', 'wujj@email.com',      '广东', '东莞', '工厂经理',        NULL,         'LOW',   1, '2020-05-20', 1520000.00,  1),
(12, 'ID_CARD', '350201****0122', '何秀英',   'PERSONAL', 2, '13910010012', 'hexy@email.com',      '福建', '厦门', '退休',            NULL,         'LOW',   1, '2015-11-01', 4800000.00,  1),
(13, 'ID_CARD', '430101****0133', '邓志鹏',   'PERSONAL', 1, '13910010013', 'dengzp@email.com',    '湖南', '长沙', '房地产',          NULL,         'HIGH',  1, '2018-08-08', 8500000.00,  1),
(14, 'ID_CARD', '440301****0144', '曾玉兰',   'PERSONAL', 2, '13910010014', 'zengyl@email.com',    '广东', '深圳', '自由职业',        NULL,         'LOW',   1, '2023-06-01', 180000.00,   1),
-- 对公客户
(15, 'BUSINESS_LICENSE', '914403****001', '深圳华创科技有限公司',       'COMPANY', NULL, '0755-86020001', 'hckj@email.com',    '广东', '深圳', NULL, '互联网科技',     'LOW',   1, '2018-05-01', 25000000.00, 1),
(16, 'BUSINESS_LICENSE', '914401****002', '广州天行进出口贸易有限公司',  'COMPANY', NULL, '020-38890001', 'tianxing@email.com','广东', '广州', NULL, '国际贸易',       'MEDIUM',1, '2019-08-15', 18000000.00, 1),
(17, 'BUSINESS_LICENSE', '913100****003', '上海鸿盛投资管理有限公司',    'COMPANY', NULL, '021-68890001', 'hstz@email.com',    '上海', '上海', NULL, '投资管理',       'HIGH',  1, '2017-03-10', 85000000.00, 1),
(18, 'BUSINESS_LICENSE', '911100****004', '北京智联未来科技有限公司',    'COMPANY', NULL, '010-58890001', 'zhlw@email.com',    '北京', '北京', NULL, '人工智能',       'LOW',   1, '2020-11-20', 12000000.00, 1),
(19, 'BUSINESS_LICENSE', '915101****005', '成都锦城建筑集团有限公司',    'COMPANY', NULL, '028-85590001', 'jcjz@email.com',    '四川', '成都', NULL, '建筑工程',       'MEDIUM',1, '2016-06-01', 35000000.00, 1);

-- ============================================================
-- 3. 账户
-- ============================================================
CREATE TABLE IF NOT EXISTS accounts (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_no      VARCHAR(30) NOT NULL UNIQUE COMMENT '账号',
    customer_id     BIGINT NOT NULL COMMENT '所属客户',
    branch_id       BIGINT NOT NULL COMMENT '开户网点',
    account_type    VARCHAR(20) NOT NULL COMMENT 'SAVINGS/CHECKING/CREDIT/LOAN/FOREIGN',
    currency        VARCHAR(10) DEFAULT 'CNY' COMMENT '币种',
    balance         DECIMAL(14,2) DEFAULT 0 COMMENT '余额',
    overdraft_limit DECIMAL(10,2) DEFAULT 0 COMMENT '透支额度',
    interest_rate   DECIMAL(5,3) COMMENT '利率(%)',
    open_date       DATE COMMENT '开户日',
    status          TINYINT DEFAULT 1 COMMENT '1=正常 0=冻结 2=销户',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_customer (customer_id),
    INDEX idx_branch (branch_id),
    INDEX idx_type (account_type),
    FOREIGN KEY (customer_id) REFERENCES bank_customers(id),
    FOREIGN KEY (branch_id) REFERENCES branches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户';

INSERT INTO accounts (id, account_no, customer_id, branch_id, account_type, currency, balance, overdraft_limit, interest_rate, open_date, status) VALUES
(1,  '622200****0001', 1,  1,  'SAVINGS',  'CNY', 850000.00,  0,      1.500, '2018-03-15', 1),
(2,  '622200****0002', 1,  1,  'CHECKING', 'CNY', 35000.00,   50000,  0.300, '2018-03-15', 1),
(3,  '622200****0003', 2,  3,  'SAVINGS',  'CNY', 2200000.00, 0,      1.750, '2019-06-15', 1),
(4,  '622200****0004', 2,  3,  'CHECKING', 'CNY', 68000.00,   100000, 0.300, '2019-06-15', 1),
(5,  '622200****0005', 3,  4,  'SAVINGS',  'CNY', 4200000.00, 0,      2.000, '2017-11-01', 1),
(6,  '622200****0006', 3,  4,  'CHECKING', 'CNY', 200000.00,  200000, 0.350, '2017-11-01', 1),
(7,  '622200****0007', 4,  5,  'SAVINGS',  'CNY', 890000.00,  0,      1.500, '2020-01-20', 1),
(8,  '622200****0008', 5,  7,  'SAVINGS',  'CNY', 520000.00,  0,      1.250, '2019-09-01', 1),
(9,  '622200****0009', 6,  8,  'SAVINGS',  'CNY', 180000.00,  0,      1.000, '2021-04-10', 1),
(10, '622200****0010', 7,  9,  'SAVINGS',  'CNY', 420000.00,  0,      1.250, '2020-08-05', 1),
(11, '622200****0011', 8,  6,  'CHECKING', 'CNY', 88000.00,   50000,  0.300, '2021-12-18', 1),
(12, '622200****0012', 9,  10, 'SAVINGS',  'CNY', 1680000.00, 0,      1.750, '2022-03-01', 1),
(13, '622200****0013', 11, 1,  'SAVINGS',  'CNY', 1200000.00, 0,      1.500, '2020-05-20', 1),
(14, '622200****0014', 12, 2,  'SAVINGS',  'CNY', 3500000.00, 0,      2.000, '2015-11-01', 1),
(15, '622200****0015', 13, 4,  'CHECKING', 'CNY', 120000.00,  500000, 0.400, '2018-08-08', 1),
(16, '622200****0016', 13, 4,  'SAVINGS',  'CNY', 6200000.00, 0,      2.250, '2018-08-08', 1),
(17, '622200****0017', 15, 1,  'CHECKING', 'CNY', 8500000.00, 2000000,0.350, '2018-05-01', 1),
(18, '622200****0018', 16, 3,  'CHECKING', 'CNY', 5600000.00, 1000000,0.300, '2019-08-15', 1),
(19, '622200****0019', 17, 4,  'CHECKING', 'CNY', 28500000.00,5000000,0.400, '2017-03-10', 1),
(20, '622200****0020', 18, 5,  'CHECKING', 'CNY', 4500000.00, 1000000,0.300, '2020-11-20', 1),
(21, '622200****0021', 19, 7,  'CHECKING', 'CNY', 8800000.00, 2000000,0.350, '2016-06-01', 1),
(22, '622200****0022', 4,  5,  'FOREIGN',  'USD', 25000.00,   0,      0.100, '2022-01-10', 1),
(23, '622200****0023', 10, 11, 'SAVINGS',  'CNY', 120000.00,  0,      1.000, '2022-07-15', 1),
(24, '622200****0024', 14, 1,  'SAVINGS',  'CNY', 50000.00,   0,      0.500, '2023-06-01', 1);

-- ============================================================
-- 4. 交易流水
-- ============================================================
CREATE TABLE IF NOT EXISTS transactions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    trans_no        VARCHAR(50) NOT NULL UNIQUE COMMENT '交易流水号',
    account_id      BIGINT NOT NULL COMMENT '账户ID',
    trans_type      VARCHAR(30) NOT NULL COMMENT 'DEPOSIT/WITHDRAW/TRANSFER/PAYMENT/INTEREST/FEE',
    amount          DECIMAL(14,2) NOT NULL COMMENT '交易金额(正=入账 负=出账)',
    balance_before  DECIMAL(14,2) NOT NULL COMMENT '交易前余额',
    balance_after   DECIMAL(14,2) NOT NULL COMMENT '交易后余额',
    counterparty    VARCHAR(200) COMMENT '交易对手',
    channel         VARCHAR(30) COMMENT 'ONLINE/COUNTER/ATM/MOBILE',
    description     VARCHAR(500) COMMENT '交易描述',
    trans_date      DATETIME NOT NULL COMMENT '交易时间',
    status          VARCHAR(20) DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED/PENDING',
    INDEX idx_account (account_id),
    INDEX idx_type (trans_type),
    INDEX idx_date (trans_date),
    FOREIGN KEY (account_id) REFERENCES accounts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易流水';

INSERT INTO transactions (id, trans_no, account_id, trans_type, amount, balance_before, balance_after, counterparty, channel, description, trans_date, status) VALUES
-- 2024年交易
(1,   'TXN20240101001', 1,  'DEPOSIT',    500000.00,  0,       500000,     '工资代发-华创科技',    'COUNTER', '2024年1月工资',              '2024-01-05 10:00:00', 'SUCCESS'),
(2,   'TXN20240101002', 2,  'TRANSFER',   15000.00,   0,       15000,      '跨行转账-李雪梅',      'MOBILE',  '转账给朋友',                '2024-01-08 14:30:00', 'SUCCESS'),
(3,   'TXN20240201001', 1,  'DEPOSIT',    500000.00,  500000,  1000000,    '工资代发-华创科技',    'COUNTER', '2024年2月工资',              '2024-02-05 10:00:00', 'SUCCESS'),
(4,   'TXN20240301001', 1,  'DEPOSIT',    500000.00,  1000000, 1500000,    '工资代发-华创科技',    'COUNTER', '2024年3月工资',              '2024-03-05 10:00:00', 'SUCCESS'),
(5,   'TXN20240301002', 2,  'PAYMENT',    -5000.00,   15000,   10000,      '物业费-南山花园',      'MOBILE',  '一季度物业费',              '2024-03-15 09:00:00', 'SUCCESS'),
(6,   'TXN20240401001', 5,  'DEPOSIT',    2000000.00, 2200000, 4200000,    '理财到期回款',         'COUNTER', '大额存单到期',              '2024-04-01 10:30:00', 'SUCCESS'),
(7,   'TXN20240501001', 3,  'DEPOSIT',    800000.00,  1400000, 2200000,    '理财分红',             'ONLINE',  '基金分红入账',              '2024-05-10 14:00:00', 'SUCCESS'),
(8,   'TXN20240601001', 1,  'WITHDRAW',   -200000.00, 1500000, 1300000,    '现金取款-柜台',        'COUNTER', '取现装修',                  '2024-06-20 11:00:00', 'SUCCESS'),
(9,   'TXN20240701001', 7,  'DEPOSIT',    500000.00,  390000,  890000,     '养老金入账',           'COUNTER', '2024年7月养老金',            '2024-07-05 09:00:00', 'SUCCESS'),
(10,  'TXN20240801001', 13, 'DEPOSIT',    500000.00,  700000,  1200000,    '销售回款',            'COUNTER', '货款回笼',                  '2024-08-10 10:00:00', 'SUCCESS'),
(11,  'TXN20240901001', 3,  'TRANSFER',   -500000.00, 2700000, 2200000,    '转出-张伟明',          'MOBILE',  '跨行转账购房首付',          '2024-09-15 15:00:00', 'SUCCESS'),
(12,  'TXN20241001001', 5,  'WITHDRAW',   -1000000.00,5200000,4200000,    '大额取款',             'COUNTER', '购买理财',                  '2024-10-08 10:00:00', 'SUCCESS'),
(13,  'TXN20241101001', 9,  'DEPOSIT',    50000.00,   130000,  180000,     '工资入账',             'MOBILE',  '2024年11月工资',             '2024-11-05 09:30:00', 'SUCCESS'),
(14,  'TXN20241201001', 14, 'DEPOSIT',    500000.00,  3000000, 3500000,    '退休金+投资收益',      'COUNTER', '年终分红',                  '2024-12-20 10:00:00', 'SUCCESS'),
-- 2025年交易
(15,  'TXN20250101001', 1,  'DEPOSIT',    500000.00,  1300000, 1800000,    '工资代发-华创科技',    'COUNTER', '2025年1月工资',              '2025-01-05 10:00:00', 'SUCCESS'),
(16,  'TXN20250201001', 2,  'PAYMENT',    -8000.00,   10000,   2000,       '水电燃气',             'MOBILE',  '2025年2月水电费',            '2025-02-10 08:30:00', 'SUCCESS'),
(17,  'TXN20250201002', 11, 'DEPOSIT',    150000.00,  0,       150000,     '货款入账',             'ONLINE',  '电商平台回款',              '2025-02-15 10:00:00', 'SUCCESS'),
(18,  'TXN20250301001', 5,  'DEPOSIT',    1000000.00, 4200000, 5200000,    '理财到期',             'COUNTER', '结构性存款到期',            '2025-03-01 10:00:00', 'SUCCESS'),
(19,  'TXN20250301002', 8,  'WITHDRAW',   -100000.00, 620000,  520000,     '取款-个体经营',         'COUNTER', '进货付款',                  '2025-03-10 14:00:00', 'SUCCESS'),
(20,  'TXN20250401001', 3,  'TRANSFER',   -200000.00, 2200000, 2000000,    '转出-投资款',           'MOBILE',  '证券入金',                  '2025-04-08 11:00:00', 'SUCCESS'),
(21,  'TXN20250401002', 12, 'DEPOSIT',    1200000.00, 2300000, 3500000,    '房屋出售款',            'COUNTER', '卖房款入账',                '2025-04-20 09:00:00', 'SUCCESS'),
(22,  'TXN20250501001', 6,  'TRANSFER',   50000.00,   150000,  200000,     '转入-张伟明',           'MOBILE',  '备用金转入',                '2025-05-10 16:00:00', 'SUCCESS'),
(23,  'TXN20250601001', 1,  'WITHDRAW',   -300000.00, 1800000, 1500000,    '大额取款',             'COUNTER', '购车付款',                  '2025-06-15 09:00:00', 'SUCCESS'),
(24,  'TXN20250701001', 16, 'DEPOSIT',    5000000.00, 1200000, 6200000,    '股票减持回款',          'COUNTER', '股票减持',                  '2025-07-10 10:00:00', 'SUCCESS'),
(25,  'TXN20250801001', 14, 'DEPOSIT',    300000.00,  3500000, 3800000,    '理财收益',             'ONLINE',  '理财产品分红',              '2025-08-15 09:30:00', 'SUCCESS'),
(26,  'TXN20250901001', 17, 'TRANSFER',   3000000.00, 5500000, 8500000,    '客户回款',             'ONLINE',  '项目款收入',                '2025-09-01 14:00:00', 'SUCCESS'),
(27,  'TXN20251001001', 13, 'DEPOSIT',    300000.00,  1200000, 1500000,    '租金收入',             'MOBILE',  '厂房租金',                  '2025-10-05 10:00:00', 'SUCCESS'),
(28,  'TXN20251101001', 21, 'TRANSFER',   -2000000.00,10800000,8800000,   '供应商付款',            'ONLINE',  '材料采购款',                '2025-11-10 15:00:00', 'SUCCESS'),
(29,  'TXN20251201001', 3,  'DEPOSIT',    1200000.00, 2000000, 3200000,    '年终奖金',             'COUNTER', '2025年奖金',                '2025-12-20 10:00:00', 'SUCCESS'),
(30,  'TXN20251201002', 19, 'TRANSFER',   -5000000.00,33500000,28500000,  '投资款',               'ONLINE',  '对外投资支出',              '2025-12-25 10:00:00', 'SUCCESS'),
-- 2026年交易
(31,  'TXN20260101001', 1,  'DEPOSIT',    500000.00,  1500000, 2000000,    '工资代发-华创科技',    'COUNTER', '2026年1月工资',              '2026-01-05 10:00:00', 'SUCCESS'),
(32,  'TXN20260201001', 5,  'DEPOSIT',    800000.00,  4200000, 5000000,    '理财分红',             'ONLINE',  '基金分红',                  '2026-02-10 14:00:00', 'SUCCESS'),
(33,  'TXN20260201002', 11, 'DEPOSIT',    200000.00,  150000,  350000,     '电商回款',             'MOBILE',  '平台结算款',                '2026-02-20 10:00:00', 'SUCCESS'),
(34,  'TXN20260301001', 17, 'TRANSFER',   -2000000.00,8500000, 6500000,    '工程款支付',            'ONLINE',  '项目分包款',                '2026-03-05 14:00:00', 'SUCCESS'),
(35,  'TXN20260301002', 1,  'WITHDRAW',   -50000.00,  2000000, 1950000,    '现金取款',             'ATM',     '日常取款',                  '2026-03-15 09:00:00', 'SUCCESS'),
(36,  'TXN20260401001', 4,  'PAYMENT',    -12000.00,  80000,   68000,      '保险缴费',             'MOBILE',  '年度车险',                  '2026-04-01 08:00:00', 'SUCCESS'),
(37,  'TXN20260401002', 15, 'TRANSFER',   -80000.00,  200000,  120000,     '转账-邓志鹏',           'MOBILE',  '个人转账',                  '2026-04-18 16:00:00', 'SUCCESS'),
(38,  'TXN20260501001', 8,  'DEPOSIT',    80000.00,   520000,  600000,     '营业收入',             'MOBILE',  '小店流水入账',              '2026-05-10 10:00:00', 'SUCCESS'),
(39,  'TXN20260501002', 18, 'TRANSFER',   2000000.00, 3600000, 5600000,    '国外货款',             'ONLINE',  '出口贸易款',                '2026-05-15 14:00:00', 'SUCCESS'),
(40,  'TXN20260601001', 3,  'WITHDRAW',   -500000.00, 5800000, 5300000,    '大额取款',             'COUNTER', '购房尾款',                  '2026-06-10 11:00:00', 'SUCCESS'),
(41,  'TXN20260601002', 11, 'PAYMENT',    -15000.00,  350000,  335000,     'POS消费',              'MOBILE',  '商场购物',                  '2026-06-15 19:30:00', 'SUCCESS'),
(42,  'TXN20260601003', 16, 'TRANSFER',   -500000.00, 6200000, 5700000,    '转出-投资',             'ONLINE',  '信托产品',                  '2026-06-20 10:00:00', 'SUCCESS'),
-- 异常交易
(43,  'TXN20260301999', 15, 'TRANSFER',   -5000000.00,200000, -4800000,   '可疑转账-海外账户',     'ONLINE',  '异常大额转出',              '2026-03-20 02:00:00', 'FAILED'),
(44,  'TXN20260501999', 5,  'TRANSFER',   -10000000.00,5000000,-5000000, '异常转账',              'MOBILE',  '余额不足交易',              '2026-05-01 01:30:00', 'FAILED');

-- ============================================================
-- 5. 贷款
-- ============================================================
CREATE TABLE IF NOT EXISTS loans (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    loan_no         VARCHAR(30) NOT NULL UNIQUE COMMENT '贷款编号',
    customer_id     BIGINT NOT NULL COMMENT '客户ID',
    branch_id       BIGINT NOT NULL COMMENT '经办网点',
    loan_type       VARCHAR(30) NOT NULL COMMENT 'MORTGAGE/CONSUMER/BUSINESS/CREDITLINE',
    amount          DECIMAL(14,2) NOT NULL COMMENT '贷款金额',
    balance         DECIMAL(14,2) NOT NULL COMMENT '剩余本金',
    interest_rate   DECIMAL(5,3) COMMENT '年利率(%)',
    term_months     INT COMMENT '期限(月)',
    start_date      DATE COMMENT '放款日',
    due_date        DATE COMMENT '到期日',
    overdue_months  INT DEFAULT 0 COMMENT '逾期月数',
    risk_rating     VARCHAR(20) DEFAULT 'NORMAL' COMMENT 'NORMAL/WATCH/SUBDEFAULT/LOSS',
    collateral      VARCHAR(200) COMMENT '抵押物',
    status          VARCHAR(20) NOT NULL COMMENT 'ACTIVE/CLOSED/OVERDUE/BAD',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_customer (customer_id),
    INDEX idx_branch (branch_id),
    INDEX idx_risk (risk_rating),
    INDEX idx_status (status),
    FOREIGN KEY (customer_id) REFERENCES bank_customers(id),
    FOREIGN KEY (branch_id) REFERENCES branches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='贷款';

INSERT INTO loans (id, loan_no, customer_id, branch_id, loan_type, amount, balance, interest_rate, term_months, start_date, due_date, overdue_months, risk_rating, collateral, status) VALUES
(1,  'LN202001001', 1,  1,  'MORTGAGE',  2000000.00, 1350000.00, 4.650, 240, '2020-03-01', '2040-03-01', 0, 'NORMAL',   '深圳南山房产',          'ACTIVE'),
(2,  'LN202101002', 2,  3,  'CONSUMER',  500000.00,  280000.00,  5.200, 60,  '2021-06-15', '2026-06-15', 0, 'NORMAL',   '广州天河房产',          'ACTIVE'),
(3,  'LN202201003', 3,  4,  'MORTGAGE',  3000000.00, 2650000.00, 4.350, 180, '2022-01-10', '2037-01-10', 0, 'NORMAL',   '上海浦东房产',          'ACTIVE'),
(4,  'LN202204004', 5,  7,  'BUSINESS',  800000.00,  550000.00,  5.800, 36,  '2022-04-20', '2025-04-20', 14, 'SUBDEFAULT','成都商铺',              'OVERDUE'),
(5,  'LN202301005', 9,  10, 'CONSUMER',  300000.00,  220000.00,  4.900, 48,  '2023-01-15', '2027-01-15', 0, 'NORMAL',   '无抵押',                'ACTIVE'),
(6,  'LN202303006', 13, 4,  'BUSINESS',  5000000.00, 3800000.00, 5.500, 60,  '2023-03-10', '2028-03-10', 3, 'SUBDEFAULT','湖南长沙房产+土地',     'OVERDUE'),
(7,  'LN202305007', 15, 1,  'BUSINESS',  2000000.00, 1200000.00, 4.800, 36,  '2023-05-01', '2026-05-01', 1, 'WATCH',    '公司股权质押',          'OVERDUE'),
(8,  'LN202308008', 17, 4,  'BUSINESS',  8000000.00, 6500000.00, 4.200, 120, '2023-08-20', '2033-08-20', 0, 'NORMAL',   '上海办公楼抵押',        'ACTIVE'),
(9,  'LN202401009', 16, 3,  'BUSINESS',  3000000.00, 2500000.00, 4.650, 60,  '2024-01-10', '2029-01-10', 0, 'NORMAL',   '广州仓库抵押',          'ACTIVE'),
(10, 'LN202403010', 19, 7,  'BUSINESS',  5000000.00, 4200000.00, 5.000, 48,  '2024-03-01', '2028-03-01', 0, 'NORMAL',   '施工设备抵押',          'ACTIVE'),
(11, 'LN202406011', 11, 1,  'CONSUMER',  100000.00,  65000.00,   6.000, 24,  '2024-06-01', '2026-06-01', 0, 'NORMAL',   '无抵押',                'ACTIVE'),
(12, 'LN202501012', 8,  6,  'CONSUMER',  200000.00,  180000.00,  5.500, 36,  '2025-01-20', '2028-01-20', 0, 'NORMAL',   '无抵押',                'ACTIVE'),
(13, 'LN202503013', 12, 2,  'MORTGAGE',  1500000.00, 1450000.00, 4.000, 120, '2025-03-15', '2035-03-15', 0, 'NORMAL',   '厦门海沧区房产',        'ACTIVE'),
-- 已结清
(14, 'LN201901014', 12, 2,  'MORTGAGE',  1000000.00, 0,          4.900, 60,  '2019-06-01', '2024-06-01', 0, 'NORMAL',   '厦门房产',              'CLOSED'),
-- 坏账
(15, 'LN202202015', 6,  8,  'CONSUMER',  50000.00,   45000.00,   7.200, 24,  '2022-02-10', '2024-02-10', 28, 'LOSS',     '无抵押',                'BAD');

-- ============================================================
-- 6. 银行员工（含敏感薪资）
-- ============================================================
CREATE TABLE IF NOT EXISTS bank_employees (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    emp_no          VARCHAR(20) NOT NULL UNIQUE COMMENT '工号',
    name            VARCHAR(100) NOT NULL COMMENT '姓名',
    gender          TINYINT COMMENT '1=男 2=女',
    phone           VARCHAR(50) COMMENT '电话',
    email           VARCHAR(200) COMMENT '邮箱',
    branch_id       BIGINT NOT NULL COMMENT '所属网点',
    department      VARCHAR(50) NOT NULL COMMENT '部门',
    position        VARCHAR(100) COMMENT '职位',
    salary_base     DECIMAL(10,2) NOT NULL COMMENT '基本月薪',
    salary_bonus    DECIMAL(10,2) DEFAULT 0 COMMENT '月度绩效奖金',
    hire_date       DATE COMMENT '入职日期',
    performance_rating TINYINT COMMENT '绩效评级 1-5',
    status          TINYINT DEFAULT 1 COMMENT '1=在职 0=离职',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_branch (branch_id),
    INDEX idx_dept (department),
    FOREIGN KEY (branch_id) REFERENCES branches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='银行员工(含薪酬)';

INSERT INTO bank_employees (id, emp_no, name, gender, phone, email, branch_id, department, position, salary_base, salary_bonus, hire_date, performance_rating, status) VALUES
(1,  'BEMP-001', '杨国栋', 1, '13800001001', 'yangguodong@bank.com', 4,  '公司金融部',   '支行行长',           58000.00, 15000.00, '2010-01-01', 5, 1),
(2,  'BEMP-002', '宋佳',   2, '13800001002', 'songjia@bank.com',     1,  '个人金融部',   '理财经理',           25000.00, 8000.00,  '2015-03-15', 4, 1),
(3,  'BEMP-003', '高峰',   1, '13800001003', 'gaofeng@bank.com',     3,  '公司金融部',   '客户经理',           28000.00, 10000.00, '2014-06-01', 5, 1),
(4,  'BEMP-004', '刘娜',   2, '13800001004', 'liuna@bank.com',       2,  '运营部',       '柜员主管',           18000.00, 5000.00,  '2017-09-10', 3, 1),
(5,  'BEMP-005', '王磊',   1, '13800001005', 'wanglei@bank.com',     5,  '风险合规部',   '风控经理',           35000.00, 10000.00, '2012-11-20', 4, 1),
(6,  'BEMP-006', '马琳琳', 2, '13800001006', 'malinlin@bank.com',    4,  '个人金融部',   '高级理财顾问',       32000.00, 12000.00, '2016-04-01', 5, 1),
(7,  'BEMP-007', '张大伟', 1, '13800001007', 'zhangdawei@bank.com',  6,  '运营部',       '柜员',               12000.00, 3000.00,  '2020-08-01', 3, 1),
(8,  'BEMP-008', '李婷',   2, '13800001008', 'liting@bank.com',      7,  '运营部',       '柜员',               10000.00, 2500.00,  '2021-05-15', 2, 1),
(9,  'BEMP-009', '赵强',   1, '13800001009', 'zhaoqiang@bank.com',   8,  '公司金融部',   '客户经理',           22000.00, 7000.00,  '2018-02-20', 4, 1),
(10, 'BEMP-010', '许静',   2, '13800001010', 'xujing@bank.com',      1,  '风险合规部',   '合规专员',           15000.00, 4000.00,  '2019-07-01', 3, 1),
(11, 'BEMP-011', '韩磊',   1, '13800001011', 'hanlei@bank.com',      9,  '运营部',       '柜员主管',           16000.00, 4000.00,  '2019-10-10', 3, 1),
(12, 'BEMP-012', '曹雯',   2, '13800001012', 'caowen@bank.com',      10, '运营部',       '柜员',               9000.00,  2000.00,  '2022-04-01', 2, 1),
(13, 'BEMP-013', '吕建国', 1, '13800001013', 'lvjianguo@bank.com',   11, '个人金融部',   '理财经理',           20000.00, 6000.00,  '2018-08-15', 3, 1),
(14, 'BEMP-014', '胡晶晶', 2, '13800001014', 'hujingjing@bank.com',  3,  '运营部',       '大堂经理',           15000.00, 3500.00,  '2020-11-01', 4, 1),
(15, 'BEMP-015', '孙浩',   1, '13800001015', 'sunhao@bank.com',      5,  '公司金融部',   '客户经理',           25000.00, 8000.00,  '2017-03-20', 4, 1),
(16, 'BEMP-016', '白洁',   2, '13800001016', 'baijie@bank.com',      2,  '个人金融部',   '理财经理',           20000.00, 6000.00,  '2019-05-01', 3, 1),
(17, 'BEMP-017', '魏永强', 1, '13800001017', 'weiyongqiang@bank.com',1,  '运营部',       '柜员',               9500.00,  2000.00,  '2022-09-15', 2, 1),
(18, 'BEMP-018', '赵小燕', 2, '13800001018', 'zhaoxiaoyan@bank.com', 5,  '运营部',       '柜员',               11000.00, 2500.00,  '2021-12-01', 3, 1);

-- ============================================================
-- 7. 信用卡
-- ============================================================
CREATE TABLE IF NOT EXISTS credit_cards (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_no         VARCHAR(30) NOT NULL UNIQUE COMMENT '卡号',
    customer_id     BIGINT NOT NULL COMMENT '持卡人',
    card_type       VARCHAR(20) NOT NULL COMMENT 'GOLD/PLATINUM/DIAMOND/INFINITE',
    credit_limit    DECIMAL(12,2) NOT NULL COMMENT '授信额度',
    used_limit      DECIMAL(12,2) DEFAULT 0 COMMENT '已用额度',
    bill_date       TINYINT COMMENT '账单日',
    payment_due     TINYINT COMMENT '还款日',
    annual_fee      DECIMAL(6,2) COMMENT '年费',
    points          INT DEFAULT 0 COMMENT '积分',
    open_date       DATE COMMENT '开卡日',
    status          TINYINT DEFAULT 1 COMMENT '1=正常 0=冻结 2=销户',
    INDEX idx_customer (customer_id),
    FOREIGN KEY (customer_id) REFERENCES bank_customers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信用卡';

INSERT INTO credit_cards (id, card_no, customer_id, card_type, credit_limit, used_limit, bill_date, payment_due, annual_fee, points, open_date, status) VALUES
(1,  '620001****0001', 1,  'PLATINUM',  100000.00,  32000.00,  10, 28, 2000.00, 12500, '2019-03-01', 1),
(2,  '620001****0002', 2,  'DIAMOND',   200000.00,  85000.00,  15, 3,  5000.00, 36800, '2020-06-15', 1),
(3,  '620001****0003', 3,  'INFINITE',  500000.00,  210000.00, 5,  20, 1000.00, 89000, '2018-11-01', 1),
(4,  '620001****0004', 4,  'GOLD',      50000.00,   12000.00,  20, 8,  500.00,  3200,  '2021-01-20', 1),
(5,  '620001****0005', 5,  'GOLD',      60000.00,   45000.00,  12, 26, 500.00,  1800,  '2020-09-01', 1),
(6,  '620001****0006', 7,  'PLATINUM',  80000.00,   15000.00,  8,  22, 2000.00, 5600,  '2021-08-05', 1),
(7,  '620001****0007', 9,  'PLATINUM',  150000.00,  98000.00,  18, 6,  2000.00, 15000, '2020-03-01', 1),
(8,  '620001****0008', 11, 'GOLD',      30000.00,   28000.00,  3,  18, 500.00,  800,   '2022-05-20', 1),
(9,  '620001****0009', 13, 'DIAMOND',   300000.00,  280000.00, 25, 13, 5000.00, 22000, '2019-08-08', 0),
(10, '620001****0010', 8,  'PLATINUM',  80000.00,   5000.00,   1,  15, 2000.00, 2000,  '2022-12-18', 1);

-- ============================================================
-- 8. 理财产品
-- ============================================================
CREATE TABLE IF NOT EXISTS wealth_products (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_code    VARCHAR(30) NOT NULL UNIQUE COMMENT '产品代码',
    name            VARCHAR(200) NOT NULL COMMENT '产品名称',
    product_type    VARCHAR(30) NOT NULL COMMENT 'FIXED/MIXED/EQUITY/STRUCTURED',
    risk_level      VARCHAR(10) NOT NULL COMMENT 'R1/R2/R3/R4/R5',
    annual_return   DECIMAL(5,2) COMMENT '预期年化(%)',
    min_amount      DECIMAL(12,2) COMMENT '起投金额',
    term_days       INT COMMENT '期限(天)',
    total_issue     DECIMAL(16,2) COMMENT '发行总规模(元)',
    status          TINYINT DEFAULT 1 COMMENT '1=在售 0=售罄',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='理财产品';

INSERT INTO wealth_products (id, product_code, name, product_type, risk_level, annual_return, min_amount, term_days, total_issue, status) VALUES
(1,  'WP-24001', '安心宝90天',         'FIXED',     'R1', 2.85,  1000.00,   90,  5000000000.00, 1),
(2,  'WP-24002', '稳健收益180天',      'FIXED',     'R2', 3.20,  5000.00,   180, 3000000000.00, 1),
(3,  'WP-24003', '双季添利365天',      'MIXED',     'R2', 3.60,  10000.00,  365, 2000000000.00, 1),
(4,  'WP-24004', '年年有余1年期',       'FIXED',     'R2', 3.80,  50000.00,  365, 8000000000.00, 1),
(5,  'WP-24005', '进取混合3年',        'MIXED',     'R3', 5.00,  100000.00, 1095,1500000000.00, 1),
(6,  'WP-24006', '证券精选FOF',        'EQUITY',    'R4', 7.50,  1000000.00,9999,500000000.00,  1),
(7,  'WP-24007', '挂钩黄金结构性存款', 'STRUCTURED','R2', 4.50,  50000.00,  270, 1000000000.00, 1),
(8,  'WP-24008', '活期宝(随时赎回)',    'FIXED',     'R1', 2.30,  1.00,      1,   20000000000.00, 1);

-- ============================================================
-- 9. 持仓表
-- ============================================================
CREATE TABLE IF NOT EXISTS holdings (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id      BIGINT NOT NULL,
    product_id      BIGINT NOT NULL,
    invested_amount DECIMAL(14,2) NOT NULL COMMENT '投入金额',
    current_value   DECIMAL(14,2) COMMENT '当前市值',
    profit_loss     DECIMAL(14,2) COMMENT '盈亏',
    purchase_date   DATE COMMENT '购买日',
    status          TINYINT DEFAULT 1 COMMENT '1=持有 0=已赎回',
    INDEX idx_account (account_id),
    INDEX idx_product (product_id),
    FOREIGN KEY (account_id) REFERENCES accounts(id),
    FOREIGN KEY (product_id) REFERENCES wealth_products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='理财持仓';

INSERT INTO holdings (id, account_id, product_id, invested_amount, current_value, profit_loss, purchase_date, status) VALUES
(1,  1,  4, 2000000.00, 2180000.00, 180000.00,  '2024-01-15', 1),
(2,  1,  1, 500000.00,  506000.00,  6000.00,    '2025-03-01', 1),
(3,  3,  5, 2000000.00, 2300000.00, 300000.00,  '2024-06-01', 1),
(4,  5,  6, 3000000.00, 3500000.00, 500000.00,  '2023-09-10', 1),
(5,  7,  2, 500000.00,  512000.00,  12000.00,   '2024-03-15', 1),
(6,  12, 4, 1500000.00, 1560000.00, 60000.00,   '2024-10-01', 1),
(7,  13, 1, 200000.00,  203000.00,  3000.00,    '2025-01-10', 1),
(8,  14, 3, 1000000.00, 1070000.00, 70000.00,   '2024-07-20', 1),
(9,  16, 5, 3000000.00, 3400000.00, 400000.00,  '2023-12-01', 1),
(10, 1,  7, 1000000.00, 1080000.00, 80000.00,   '2025-02-15', 1);

-- ============================================================
-- 验证
-- ============================================================
SELECT 'Bank Customers' AS tbl, COUNT(*) AS cnt FROM bank_customers
UNION ALL SELECT 'Accounts', COUNT(*) FROM accounts
UNION ALL SELECT 'Transactions', COUNT(*) FROM transactions
UNION ALL SELECT 'Loans', COUNT(*) FROM loans
UNION ALL SELECT 'Bank Employees', COUNT(*) FROM bank_employees
UNION ALL SELECT 'Branches', COUNT(*) FROM branches
UNION ALL SELECT 'Credit Cards', COUNT(*) FROM credit_cards
UNION ALL SELECT 'Wealth Products', COUNT(*) FROM wealth_products
UNION ALL SELECT 'Holdings', COUNT(*) FROM holdings;
