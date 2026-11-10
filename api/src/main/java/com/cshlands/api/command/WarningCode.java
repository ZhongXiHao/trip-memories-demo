package com.cshlands.api.command;

/**
 * 与契约「规则指令」里 warnings 的 code 一一对应。常量名就是对外的 code，不能随意改名。
 */
enum WarningCode {
    CURRENCY_DEFAULTED,
    SPLIT_ALL_MEMBERS,
    PAYER_ADDED_TO_SPLIT
}
