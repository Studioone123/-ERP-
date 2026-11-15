-- ---------------------------------------------------------------------------
-- 汇智 ERP 建表脚本（H2 MODE=MySQL / MySQL 8 均可直接执行）
-- 所有表使用 IF NOT EXISTS，服务每次启动执行也不会破坏已有数据
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(128) NOT NULL,
    real_name   VARCHAR(64),
    role        VARCHAR(32)  DEFAULT 'ADMIN',
    status      INT          DEFAULT 1,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS warehouse (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32) NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    address     VARCHAR(200),
    status      INT DEFAULT 1,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS supplier (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32) NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    contact     VARCHAR(64),
    phone       VARCHAR(32),
    address     VARCHAR(200),
    status      INT DEFAULT 1,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS customer (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32) NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    contact     VARCHAR(64),
    phone       VARCHAR(32),
    address     VARCHAR(200),
    status      INT DEFAULT 1,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS product (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(32) NOT NULL UNIQUE,
    name          VARCHAR(100) NOT NULL,
    category      VARCHAR(64),
    spec          VARCHAR(100),
    unit          VARCHAR(16) DEFAULT '个',
    cost_price    DECIMAL(12,2) DEFAULT 0,
    sale_price    DECIMAL(12,2) DEFAULT 0,
    safety_stock  INT DEFAULT 0,
    status        INT DEFAULT 1,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS purchase_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32) NOT NULL UNIQUE,
    supplier_id   BIGINT NOT NULL,
    warehouse_id  BIGINT NOT NULL,
    total_amount  DECIMAL(14,2) DEFAULT 0,
    status        VARCHAR(20) DEFAULT 'DRAFT',
    remark        VARCHAR(255),
    created_by    VARCHAR(64),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    confirm_at    TIMESTAMP,
    finish_at     TIMESTAMP
);

CREATE TABLE IF NOT EXISTS purchase_item (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT NOT NULL,
    product_id   BIGINT NOT NULL,
    quantity     INT NOT NULL,
    unit_price   DECIMAL(12,2) NOT NULL,
    amount       DECIMAL(14,2) NOT NULL,
    received_qty INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sales_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32) NOT NULL UNIQUE,
    customer_id   BIGINT NOT NULL,
    warehouse_id  BIGINT NOT NULL,
    total_amount  DECIMAL(14,2) DEFAULT 0,
    status        VARCHAR(20) DEFAULT 'DRAFT',
    remark        VARCHAR(255),
    created_by    VARCHAR(64),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    confirm_at    TIMESTAMP,
    finish_at     TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sales_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    quantity    INT NOT NULL,
    unit_price  DECIMAL(12,2) NOT NULL,
    amount      DECIMAL(14,2) NOT NULL,
    shipped_qty INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS inventory (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_id  BIGINT NOT NULL,
    product_id    BIGINT NOT NULL,
    quantity      INT DEFAULT 0,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_inventory UNIQUE (warehouse_id, product_id)
);

CREATE TABLE IF NOT EXISTS stock_flow (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    flow_no       VARCHAR(32),
    biz_type      VARCHAR(24),
    biz_no        VARCHAR(32),
    warehouse_id  BIGINT,
    product_id    BIGINT,
    change_qty    INT,
    before_qty    INT,
    after_qty     INT,
    operator      VARCHAR(64),
    remark        VARCHAR(255),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64),
    action      VARCHAR(64),
    detail      VARCHAR(500),
    ip          VARCHAR(64),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ===========================================================================
-- 以下为「借鉴金蝶 K/3」扩展模块，全部使用 IF NOT EXISTS，
-- 老数据库升级时只新增表、不改动已有表，已有业务数据完全保留。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 一、财务会计：对应金蝶 K/3「总账系统 - 基础资料 / 凭证管理 / 期末处理」
-- 设计要点：不冗余存储科目余额，余额一律由「已过账凭证分录」实时汇总。
--           好处是不会出现「余额表与凭证不一致」的脏数据，代价是查询略慢，
--           对中小企业账套完全够用（金蝶则用余额表 + 过账更新，属于性能优化）。
-- ---------------------------------------------------------------------------

-- 会计科目表（金蝶叫「科目」）。category 决定报表归属，direction 决定余额算法。
CREATE TABLE IF NOT EXISTS fin_subject (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(20)  NOT NULL UNIQUE,   -- 科目编码，如 1002 / 6001
    name         VARCHAR(64)  NOT NULL,          -- 科目名称
    category     VARCHAR(20)  NOT NULL,          -- ASSET资产 LIABILITY负债 EQUITY权益 COST成本 INCOME收入 EXPENSE费用
    direction    VARCHAR(10)  NOT NULL,          -- DEBIT 借方余额 / CREDIT 贷方余额
    parent_code  VARCHAR(20),                    -- 上级科目编码（支持多级科目，报表按一级汇总）
    is_leaf      INT DEFAULT 1,                  -- 1=末级科目，只有末级可以录入凭证
    status       INT DEFAULT 1,                  -- 1=启用 0=停用
    remark       VARCHAR(200),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 凭证主表（金蝶叫「记账凭证」，凭证字 + 凭证号唯一确定一张凭证）
CREATE TABLE IF NOT EXISTS fin_voucher (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_no     VARCHAR(32) NOT NULL UNIQUE,  -- 凭证号 PZ20261110-0001
    voucher_date   DATE NOT NULL,                -- 业务日期
    account_period VARCHAR(7)  NOT NULL,         -- 会计期间 YYYY-MM，结账以它为单位
    voucher_word   VARCHAR(8)  DEFAULT '记',      -- 凭证字：记 / 收 / 付 / 转
    summary        VARCHAR(255),                 -- 凭证摘要
    source_type    VARCHAR(24) DEFAULT 'MANUAL', -- MANUAL手工 / PURCHASE_IN采购入库 / SALE_OUT销售出库 / SALE_COST结转成本 / SETTLE收付款 / MATERIAL_ISSUE领料 / PRODUCT_FINISH完工 / OPENING期初
    biz_no         VARCHAR(32),                  -- 来源业务单号，实现「凭证 ↔ 单据」联查
    status         VARCHAR(16) DEFAULT 'DRAFT',  -- DRAFT草稿 / POSTED已过账 / VOID作废
    total_debit    DECIMAL(14,2) DEFAULT 0,      -- 借方合计（冗余存储便于列表展示与复核）
    total_credit   DECIMAL(14,2) DEFAULT 0,      -- 贷方合计，必须与借方相等
    attachment_no  INT DEFAULT 0,                -- 附件张数
    created_by     VARCHAR(64),
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    posted_by      VARCHAR(64),
    posted_at      TIMESTAMP
);

-- 凭证分录（一张凭证至少两行，遵循「有借必有贷、借贷必相等」）
CREATE TABLE IF NOT EXISTS fin_voucher_entry (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_id   BIGINT NOT NULL,
    line_no      INT NOT NULL,                   -- 分录序号，从 1 开始
    summary      VARCHAR(255),
    subject_code VARCHAR(20) NOT NULL,
    subject_name VARCHAR(64),
    debit        DECIMAL(14,2) DEFAULT 0,        -- 借方金额，与 credit 二选一
    credit       DECIMAL(14,2) DEFAULT 0         -- 贷方金额
);

-- 会计期间（金蝶「期末结账」：结账后该期间禁止再新增/修改凭证）
CREATE TABLE IF NOT EXISTS fin_period (
    account_period VARCHAR(7) PRIMARY KEY,
    status         VARCHAR(10) DEFAULT 'OPEN',   -- OPEN 未结账 / CLOSED 已结账
    closed_by      VARCHAR(64),
    closed_at      TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- 二、应收应付：对应金蝶 K/3「应收款管理 / 应付款管理」
-- 业务单据审核执行后自动生成往来单，收付款时按单核销，可查账龄。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fin_ar_ap (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_no        VARCHAR(32) NOT NULL UNIQUE,  -- AR2026... / AP2026...
    bill_type      VARCHAR(8)  NOT NULL,          -- AR 应收 / AP 应付
    partner_type   VARCHAR(16) NOT NULL,          -- CUSTOMER 客户 / SUPPLIER 供应商
    partner_id     BIGINT NOT NULL,
    partner_name   VARCHAR(100),
    biz_no         VARCHAR(32),                  -- 来源销售单 / 采购单
    amount         DECIMAL(14,2) NOT NULL,        -- 应收（应付）总额
    settled_amount DECIMAL(14,2) DEFAULT 0,      -- 已核销金额
    status         VARCHAR(12) DEFAULT 'OPEN',    -- OPEN未结 / PARTIAL部分结清 / CLOSED已结清
    bill_date      DATE,                          -- 单据日期（账龄起算日）
    due_date       DATE,                          -- 到期日
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 收付款核销记录（金蝶「收款单 / 付款单」）
CREATE TABLE IF NOT EXISTS fin_settlement (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    settle_no    VARCHAR(32) NOT NULL UNIQUE,
    settle_type  VARCHAR(10) NOT NULL,            -- RECEIVE 收款 / PAY 付款
    ar_ap_id     BIGINT NOT NULL,
    bill_no      VARCHAR(32),
    partner_name VARCHAR(100),
    amount       DECIMAL(14,2) NOT NULL,
    pay_method   VARCHAR(16) DEFAULT 'BANK',      -- CASH现金 / BANK银行 / TRANSFER转账
    settle_date  DATE,
    remark       VARCHAR(255),
    created_by   VARCHAR(64),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- 三、生产制造：对应金蝶 K/3「生产数据管理 BOM / 生产任务管理」
-- 简化模型：BOM 单层展开，领料按「子件用量 × 生产数量 × (1+损耗率)」扣库存，
-- 完工按成品成本价入库，并同步生成生产成本 related 会计凭证。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mf_bom (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    bom_code    VARCHAR(32) NOT NULL UNIQUE,
    product_id  BIGINT NOT NULL,                  -- 成品（产成品物料）
    version     VARCHAR(16) DEFAULT 'V1.0',
    name        VARCHAR(100),
    status      INT DEFAULT 1,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mf_bom_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    bom_id      BIGINT NOT NULL,
    material_id BIGINT NOT NULL,                  -- 子件（原材料物料）
    quantity    INT NOT NULL,                     -- 单位成品用量
    loss_rate   DECIMAL(6,2) DEFAULT 0            -- 损耗率 %，如 5 表示 5%
);

CREATE TABLE IF NOT EXISTS mf_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32) NOT NULL UNIQUE,
    product_id    BIGINT NOT NULL,
    bom_id        BIGINT,
    warehouse_id  BIGINT NOT NULL,
    quantity      INT NOT NULL,                   -- 计划生产数量
    finished_qty  INT DEFAULT 0,                  -- 已完工入库数量
    status        VARCHAR(16) DEFAULT 'DRAFT',    -- DRAFT → CONFIRMED → ISSUED → FINISHED / CANCELLED
    remark        VARCHAR(255),
    created_by    VARCHAR(64),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    confirm_at    TIMESTAMP,
    issue_at      TIMESTAMP,
    finish_at     TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- 四、组织与人资基础资料：对应金蝶「基础资料 - 部门 / 职员」
-- 为保持通用 CRUD 的一致性，职员表用部门名称而非外键，避免联表复杂度。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS department (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32) NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    manager     VARCHAR(64),
    phone       VARCHAR(32),
    status      INT DEFAULT 1,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS employee (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32) NOT NULL UNIQUE,
    name        VARCHAR(64) NOT NULL,
    department  VARCHAR(100),
    position    VARCHAR(64),
    phone       VARCHAR(32),
    status      INT DEFAULT 1,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
