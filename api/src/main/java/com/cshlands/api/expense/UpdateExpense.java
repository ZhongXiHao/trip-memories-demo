package com.cshlands.api.expense;

import com.cshlands.api.trip.TripClock;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

record UpdateExpense(
        long tripId,
        long expenseId,
        String title,
        long amountMinor,
        String currency,
        long paidByMemberId,
        long editorMemberId,
        SplitMode splitMode,
        Instant occurredAt,
        String occurredTz,
        ExpenseCategory category,
        String note,
        Integer version
) {
    static UpdateExpense fromRequest(long tripId, long expenseId, long editorMemberId, UpdateExpenseRequest request, Instant occurredAt, String occurredTz) {
        ExpenseCategory category = request.category() == null ? ExpenseCategory.OTHER : request.category();
        return new UpdateExpense(
                tripId,
                expenseId,
                request.title(),
                request.amountMinor(),
                request.currency(),
                request.paidByMemberId(),
                editorMemberId,
                request.splitMode(),
                occurredAt,
                occurredTz,
                category,
                request.note(),
                request.version()
        );
    }
}