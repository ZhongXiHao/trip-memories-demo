package com.cshlands.api.expense;

import java.time.Instant;

record ExpenseQuery(
        long tripId,
        long currentMemberId,   // 谁在看：决定可见性
        Long memberId,          // 付款人或分摊者
        String currency,
        ExpenseCategory category,
        Instant occurredFrom,   // occurredFrom、occurredTo 只是粗筛的 UTC 范围，是闭区间，精确的"当地日期"由 Service 里按 occurredTz 判断
        Instant occurredTo
) {
}
