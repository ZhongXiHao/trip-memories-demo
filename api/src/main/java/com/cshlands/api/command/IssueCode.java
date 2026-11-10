package com.cshlands.api.command;

/**
 * 与契约「规则指令」里 issues 的 code 一一对应。常量名就是对外的 code（JSON 里按 name() 输出），不能随意改名。
 */
enum IssueCode {
    NOT_A_COMMAND,
    UNKNOWN_COMMAND,
    COMMAND_NOT_AVAILABLE,
    MISSING_AMOUNT,
    AMBIGUOUS_AMOUNT,
    UNKNOWN_CURRENCY,
    AMOUNT_NOT_ROUNDABLE,
    INVALID_AMOUNT,
    MISSING_TITLE,
    TITLE_TOO_LONG,
    UNKNOWN_MEMBER,
    AMBIGUOUS_MEMBER,
    MEMBER_LEFT,
    AMBIGUOUS_PAYER
}
