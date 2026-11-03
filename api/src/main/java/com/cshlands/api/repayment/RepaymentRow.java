package com.cshlands.api.repayment;

import java.time.Instant;

/** Mapper 读出来的一行，列和 RepaymentMapper.xml 里的 rowColumns 一一对应；deletedAt 非空表示已取消 */
record RepaymentRow(
        long id,
        long tripId,
        String settlementGroupId,
        long fromMemberId,
        long toMemberId,
        long clearedAmountMinor,
        String clearedCurrency,
        long paidAmountMinor,
        String paidCurrency,
        Long expenseId,
        RepaymentStatus status,
        Instant confirmedAt,
        String note,
        long createdByMemberId,
        int version,
        Instant createdAt,
        Instant deletedAt
) {
}
