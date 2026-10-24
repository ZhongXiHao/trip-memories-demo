-- 旅行回忆 App 切片 · 第一批表：金额链路
-- 设计依据：docs/overview/schema-structure.md、trip-app-spec-plan-trip.md 4.6
--
-- 约定
--   金额：BIGINT，一律按「最小单位 × 100」存（JPY 1200 存 120000），显示时除以 100。
--   币别：ISO 4217 三位码，必须出现在 currency 表。
--   时间：DATETIME(3)，一律存 UTC。连接串需加 connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true，
--         MySQL 服务器也要设成 UTC（启动参数 --default-time-zone=+00:00），
--         否则 CURRENT_TIMESTAMP 默认值会取服务器本地时区。
--   软删除：只有 expense、repayment、trip 用 deleted_at；
--           防重复提交的唯一索引不含 deleted_at，已删除的单据重放会被拒，不会"复活"。
--   成员退出用 trip_member.status，不删行，历史账目仍能指向他。

-- 容器初始化时 mysql 客户端默认不是 utf8mb4，不声明的话中文会被二次编码入库
SET NAMES utf8mb4;

CREATE TABLE currency
(
    code          CHAR(3)     NOT NULL COMMENT 'ISO 4217',
    name          VARCHAR(32) NOT NULL,
    -- 分摊取整单位（存储单位，已含 ×100）：JPY/KRW 为 100（整元），其余 1（到分）
    rounding_unit INT         NOT NULL DEFAULT 1,
    PRIMARY KEY (code),
    CHECK (rounding_unit IN (1, 100))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='支持的币别；不支持三位小数币别';

CREATE TABLE app_user
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    nickname   VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE trip
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(128) NOT NULL,
    description      VARCHAR(512) NULL,
    start_date       DATE         NULL,
    end_date         DATE         NULL,
    timezone         VARCHAR(64)  NOT NULL DEFAULT 'UTC' COMMENT 'IANA，例如 Asia/Tokyo',
    display_currency CHAR(3)      NULL COMMENT '仅用于显示概数，不写入账目',
    created_by       BIGINT       NOT NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at       DATETIME(3)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_trip_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_trip_display_currency FOREIGN KEY (display_currency) REFERENCES currency (code),
    CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE trip_member
(
    id        BIGINT                  NOT NULL AUTO_INCREMENT,
    trip_id   BIGINT                  NOT NULL,
    user_id   BIGINT                  NOT NULL,
    role      ENUM ('owner','member') NOT NULL DEFAULT 'member',
    status    ENUM ('active','left')  NOT NULL DEFAULT 'active',
    joined_at DATETIME(3)             NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    -- owner 时为 1，否则 NULL；唯一索引允许多个 NULL，所以下面的唯一键 = 每个行程至多一个 owner。
    -- 转让 owner 要先把旧 owner 降为 member，再升新 owner。
    owner_slot TINYINT AS (IF(role = 'owner', 1, NULL)) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_trip_user (trip_id, user_id),
    UNIQUE KEY uk_member_one_owner (trip_id, owner_slot),
    -- 供子表做 (成员, 行程) 复合外键，保证成员不会被用到别的行程的账上
    UNIQUE KEY uk_member_id_trip (id, trip_id),
    KEY idx_member_user (user_id),
    CONSTRAINT fk_member_trip FOREIGN KEY (trip_id) REFERENCES trip (id),
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE expense
(
    id                   BIGINT                          NOT NULL AUTO_INCREMENT,
    trip_id              BIGINT                          NOT NULL,
    client_uuid          CHAR(36)                        NOT NULL COMMENT '客户端/Agent 草稿生成，防重复提交',
    title                VARCHAR(128)                    NOT NULL,
    amount_minor         BIGINT                          NOT NULL COMMENT '×100 的整数，实际付款币别，不换算',
    currency             CHAR(3)                         NOT NULL,
    paid_by_member_id    BIGINT                          NOT NULL COMMENT '付款人',
    created_by_member_id BIGINT                          NOT NULL COMMENT '记账人，可与付款人不同',
    split_mode           ENUM ('equal','custom','none')  NOT NULL COMMENT 'none = 个人支出，不参与分账',
    occurred_at          DATETIME(3)                     NOT NULL COMMENT 'UTC',
    occurred_tz          VARCHAR(64)                     NOT NULL COMMENT '记录当地的 IANA 时区；无 DB 默认值，应用层默认取 trip.timezone。按天分组：occurred_at 按它换算后取日期',
    category             ENUM ('flight','lodging','food','transport','shopping','ticket','other')
                                                         NOT NULL DEFAULT 'other',
    visibility           ENUM ('private','trip')         NOT NULL COMMENT '由 split_mode 决定：none=private，其余=trip；保留此列是为了各表可见性过滤写法一致',
    source               ENUM ('form','command','agent') NOT NULL DEFAULT 'form' COMMENT '来源：表单/规则指令/Agent，用于评测统计',
    note                 VARCHAR(512)                    NULL,
    version              INT                             NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_at           DATETIME(3)                     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)                     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at           DATETIME(3)                     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_expense_idem (trip_id, client_uuid),
    UNIQUE KEY uk_expense_id_trip (id, trip_id),
    KEY idx_expense_trip_time (trip_id, occurred_at),
    -- 复合外键要求有一个列顺序相同的索引，这里显式建好，避免 MySQL 再自动建重复的
    KEY idx_expense_payer (paid_by_member_id, trip_id),
    KEY idx_expense_creator (created_by_member_id, trip_id),
    CONSTRAINT fk_expense_trip FOREIGN KEY (trip_id) REFERENCES trip (id),
    CONSTRAINT fk_expense_currency FOREIGN KEY (currency) REFERENCES currency (code),
    CONSTRAINT fk_expense_payer FOREIGN KEY (paid_by_member_id, trip_id) REFERENCES trip_member (id, trip_id),
    CONSTRAINT fk_expense_creator FOREIGN KEY (created_by_member_id, trip_id) REFERENCES trip_member (id, trip_id),
    CHECK (amount_minor > 0),
    -- 个人支出 <=> private，双向
    CHECK ((split_mode = 'none') = (visibility = 'private')),
    -- 个人支出只有本人可见，不允许别人代记
    CHECK (split_mode <> 'none' OR paid_by_member_id = created_by_member_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 每笔支出的分摊者各一行；个人支出（split_mode = none）也有一行：分给付款人自己，金额 = 总额。
-- 这样对任何支出都成立：各行金额之和 = expense.amount_minor，
-- 成员余额 = 他付的 − 他的分摊，个人支出自然抵消；"我的总花费" = 我的分摊之和。
-- 金额之和、none 时只有付款人一行、金额是 rounding_unit 的整数倍，均由应用层保证并单测。
-- 本表没有 deleted_at：支出被软删除后分摊行仍在，算欠款/花费一律读下面的视图，不要直接查本表。
-- 修改分摊必须在同一事务里让 expense.version + 1，否则 expense 上的乐观锁检测不到冲突。
CREATE TABLE expense_split
(
    id           BIGINT NOT NULL AUTO_INCREMENT,
    trip_id      BIGINT NOT NULL COMMENT '冗余，用于复合外键校验同一行程',
    expense_id   BIGINT NOT NULL,
    member_id    BIGINT NOT NULL,
    amount_minor BIGINT NOT NULL COMMENT '×100 的整数，币别随 expense',
    PRIMARY KEY (id),
    -- 含 trip_id 的唯一键同时充当 fk_split_expense 的索引；expense_id 决定 trip_id，唯一性等同 (expense_id, member_id)
    UNIQUE KEY uk_split_expense_member (expense_id, trip_id, member_id),
    KEY idx_split_member (member_id, trip_id),
    CONSTRAINT fk_split_expense FOREIGN KEY (expense_id, trip_id) REFERENCES expense (id, trip_id),
    CONSTRAINT fk_split_member FOREIGN KEY (member_id, trip_id) REFERENCES trip_member (id, trip_id),
    CHECK (amount_minor >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 还款：同时保存销帐与实付两组金额，汇率为衍生值（paid / cleared），不单独存
CREATE TABLE repayment
(
    id                   BIGINT                       NOT NULL AUTO_INCREMENT,
    trip_id              BIGINT                       NOT NULL,
    client_uuid          CHAR(36)                     NOT NULL,
    from_member_id       BIGINT                       NOT NULL COMMENT '付款方',
    to_member_id         BIGINT                       NOT NULL COMMENT '收款方',
    cleared_amount_minor BIGINT                       NOT NULL COMMENT '抵销多少欠款',
    cleared_currency     CHAR(3)                      NOT NULL,
    paid_amount_minor    BIGINT                       NOT NULL COMMENT '实际转账金额',
    paid_currency        CHAR(3)                      NOT NULL,
    settlement_group_id  CHAR(36)                     NULL COMMENT '同一次转账结清多条欠款时共用',
    expense_id           BIGINT                       NULL COMMENT '针对某笔支出还款时填',
    status               ENUM ('pending','confirmed') NOT NULL DEFAULT 'pending' COMMENT '只有 confirmed 抵销欠款，pending 另列显示',
    confirmed_at         DATETIME(3)                  NULL COMMENT '确认时间；确认人即 to_member_id',
    note                 VARCHAR(512)                 NULL,
    created_by_member_id BIGINT                       NOT NULL COMMENT '只能是付款方（标记已还）或收款方（直接记已收到）',
    version              INT                          NOT NULL DEFAULT 0,
    created_at           DATETIME(3)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at           DATETIME(3)                  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_repayment_idem (trip_id, client_uuid),
    KEY idx_repayment_from (from_member_id, trip_id),
    KEY idx_repayment_to (to_member_id, trip_id),
    KEY idx_repayment_creator (created_by_member_id, trip_id),
    KEY idx_repayment_expense (expense_id, trip_id),
    KEY idx_repayment_group (settlement_group_id),
    CONSTRAINT fk_repay_trip FOREIGN KEY (trip_id) REFERENCES trip (id),
    CONSTRAINT fk_repay_from FOREIGN KEY (from_member_id, trip_id) REFERENCES trip_member (id, trip_id),
    CONSTRAINT fk_repay_to FOREIGN KEY (to_member_id, trip_id) REFERENCES trip_member (id, trip_id),
    CONSTRAINT fk_repay_creator FOREIGN KEY (created_by_member_id, trip_id) REFERENCES trip_member (id, trip_id),
    CONSTRAINT fk_repay_expense FOREIGN KEY (expense_id, trip_id) REFERENCES expense (id, trip_id),
    CONSTRAINT fk_repay_cleared_cur FOREIGN KEY (cleared_currency) REFERENCES currency (code),
    CONSTRAINT fk_repay_paid_cur FOREIGN KEY (paid_currency) REFERENCES currency (code),
    CHECK (from_member_id <> to_member_id),
    CHECK (cleared_amount_minor > 0 AND paid_amount_minor > 0),
    CHECK (cleared_currency <> paid_currency OR cleared_amount_minor = paid_amount_minor),
    CHECK (created_by_member_id IN (from_member_id, to_member_id)),
    CHECK ((status = 'confirmed') = (confirmed_at IS NOT NULL)),
    -- 已确认的还款是历史事实，只能取消待确认的；同时挡住"确认一条已删除的"
    CHECK (deleted_at IS NULL OR status = 'pending')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
-- 整组确认：UPDATE repayment SET status='confirmed', confirmed_at=?, version=version+1
--           WHERE trip_id=? AND settlement_group_id=? AND status='pending' AND deleted_at IS NULL
-- 单条语句即原子；必须排除已取消的行（确认已删除的行会违反 CHECK，整条语句失败）。
-- 组内各行 from/to 一致由应用层校验。

-- 有效分摊 = 未被软删除的支出的分摊。算欠款、算花费的查询（含 Agent 问答工具）只读这个视图。
-- 按成员统计花费时，调用方必须带 viewer：visibility = 'trip' OR created_by_member_id = viewer，
-- 否则会把别人的 private 个人支出泄露出去。
CREATE VIEW v_expense_split_active AS
SELECT s.id AS split_id,
       s.trip_id,
       s.expense_id,
       s.member_id,
       s.amount_minor,
       e.currency,
       e.paid_by_member_id,
       e.created_by_member_id,
       e.split_mode,
       e.visibility,
       e.occurred_at,
       e.occurred_tz
FROM expense_split s
         JOIN expense e ON e.id = s.expense_id AND e.trip_id = s.trip_id
WHERE e.deleted_at IS NULL;

-- 谁欠付款人多少：排除自己分给自己的那条（个人支出）。一对一净额在此基础上由 Java 计算。
CREATE VIEW v_debt_entry AS
SELECT *
FROM v_expense_split_active
WHERE member_id <> paid_by_member_id
  AND split_mode <> 'none';
