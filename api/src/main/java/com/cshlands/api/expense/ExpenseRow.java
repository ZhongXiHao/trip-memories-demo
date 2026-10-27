package com.cshlands.api.expense;

import java.time.Instant;
record ExpenseRow(
        long id,
        long tripId,
        String clientUuid,
        String title,
        long amountMinor,
        String currency,
        long paidByMemberId,
        long createdByMemberId,
        SplitMode splitMode,
        Instant occurredAt,
        String occurredTz,
        ExpenseCategory category,
        Visibility visibility,
        Source source,
        String note,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {

}
