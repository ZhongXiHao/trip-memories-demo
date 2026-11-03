package com.cshlands.api.repayment;

/** 插库用的一行。confirmed_at 不在这里：status 是 confirmed 时由 SQL 取当前时间 */
record NewRepayment(
        long tripId,
        long fromMemberId,
        long toMemberId,
        long clearedAmountMinor,
        String clearedCurrency,
        long paidAmountMinor,
        String paidCurrency,
        String settlementGroupId,
        Long expenseId,
        RepaymentStatus status,
        String note,
        long createdByMemberId
) {
}
