package com.cshlands.api.command;

import com.cshlands.api.expense.ExpenseDraft;

import java.util.List;

/**
 * 解析器的输出：响应里除了 preview 以外的部分（preview 要查库，由 CommandService 算）。
 *
 * @param command 别名归一后的指令名（pay、owe、today、todo…）；不是指令、认不出时为 null
 * @param kind    不是指令、认不出、暂未开放时为 null
 * @param draft   只在 kind=EXPENSE 时有
 * @param view    只在 kind=VIEW 时有
 */
record ParseResult(
        ParseStatus status,
        String command,
        CommandKind kind,
        ExpenseDraft draft,
        ViewTarget view,
        List<ParseIssue> issues,
        List<ParseWarning> warnings
) {
}
