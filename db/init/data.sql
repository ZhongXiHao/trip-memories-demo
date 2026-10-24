SET NAMES utf8mb4;

-- 币别种子数据。rounding_unit 为存储单位（已含 ×100）：无小数币别 100，两位小数币别 1。
INSERT INTO currency (code, name, rounding_unit) VALUES
    ('JPY', '日元', 100),
    ('KRW', '韩元', 100),
    ('CNY', '人民币', 1),
    ('HKD', '港币', 1),
    ('TWD', '新台币', 1),
    ('USD', '美元', 1),
    ('EUR', '欧元', 1),
    ('GBP', '英镑', 1),
    ('SGD', '新加坡元', 1),
    ('THB', '泰铢', 1),
    ('MYR', '马来西亚林吉特', 1),
    ('AUD', '澳元', 1);
