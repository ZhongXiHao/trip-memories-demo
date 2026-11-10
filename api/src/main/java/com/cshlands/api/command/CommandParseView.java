package com.cshlands.api.command;

import com.cshlands.api.expense.ExpenseDraft;
import com.cshlands.api.expense.ExpensePreview;

import java.util.List;

/** 解析指令的响应，字段见契约「规则指令」。8 个字段总是都在，没有值的为 null */
record CommandParseView(
        ParseStatus status,
        String command,
        CommandKind kind,
        ExpenseDraft draft,
        ExpensePreview preview,
        ViewTarget view,
        List<ParseIssue> issues,
        List<ParseWarning> warnings
) {
    static CommandParseView of(ParseResult result, ExpensePreview preview) {
        return new CommandParseView(result.status(), result.command(), result.kind(), result.draft(), preview,
                result.view(), result.issues(), result.warnings());
    }
}
