package com.cshlands.api.repayment;

import java.time.Instant;

/** 还款对象，字段和契约一致；值为 null 的字段也要输出（契约示例里就有 "expenseId": null） */
record RepaymentView(
        long id,
        String settlementGroupId,
        Long expenseId,
        long fromMemberId,
        long toMemberId,
        long clearedAmountMinor,
        String clearedCurrency,
        long paidAmountMinor,
        String paidCurrency,
        RepaymentStatus status,
        Instant confirmedAt,
        String note,
        long createdByMemberId,
        int version,
        Instant createdAt
) {
    static RepaymentView of(RepaymentRow row) {
        // 库里取消是 deleted_at，接口上统一是 status = cancelled
        RepaymentStatus status = row.deletedAt() != null ? RepaymentStatus.CANCELLED : row.status();
        return new RepaymentView(
                row.id(),
                row.settlementGroupId(),
                row.expenseId(),
                row.fromMemberId(),
                row.toMemberId(),
                row.clearedAmountMinor(),
                row.clearedCurrency(),
                row.paidAmountMinor(),
                row.paidCurrency(),
                status,
                row.confirmedAt(),
                row.note(),
                row.createdByMemberId(),
                row.version(),
                row.createdAt());
    }
}
