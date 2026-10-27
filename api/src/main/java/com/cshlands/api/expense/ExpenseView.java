package com.cshlands.api.expense;

import java.time.Instant;
import java.util.List;

record ExpenseView(
        long id,
        long tripId,
        String title,
        long amountMinor,
        String currency,
        long paidByMemberId,
        long createdByMemberId,
        SplitMode splitMode,
        Visibility visibility,
        List<SplitRow> splits,
        Instant occurredAt,
        String occurredTz,
        Long dayIndex,
        ExpenseCategory category,
        String note,
        Source source,
        int version,
        Instant createdAt
) {
    static ExpenseView of(ExpenseRow row, List<SplitRow> splits, Long dayIndex) {
        return new ExpenseView(
                row.id(),
                row.tripId(),
                row.title(),
                row.amountMinor(),
                row.currency(),
                row.paidByMemberId(),
                row.createdByMemberId(),
                row.splitMode(),
                row.visibility(),
                splits,
                row.occurredAt(),
                row.occurredTz(),
                dayIndex,
                row.category(),
                row.note(),
                row.source(),
                row.version(),
                row.createdAt()
        );
    }
}
