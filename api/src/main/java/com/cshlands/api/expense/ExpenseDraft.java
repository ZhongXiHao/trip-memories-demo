package com.cshlands.api.expense;

import java.util.List;

/**
 * 一笔支出的草稿，字段同「创建支出」的请求体（不含 clientUuid、note、category）。
 * 规则指令解析出来给前端确认，前端补上 clientUuid 原样调创建。没识别出的字段为 null。
 *
 * @param splits     只有成员（equal），金额由预览算
 * @param occurredAt null 表示创建时取"现在"
 * @param occurredTz null 表示创建时取行程时区
 */
public record ExpenseDraft(
        String title,
        Long amountMinor,
        String currency,
        Long paidByMemberId,
        SplitMode splitMode,
        List<Split> splits,
        String occurredAt,
        String occurredTz,
        Source source
) {
    public record Split(long memberId) {
    }
}
