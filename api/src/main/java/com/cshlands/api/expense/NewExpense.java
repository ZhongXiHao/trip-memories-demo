package com.cshlands.api.expense;

import com.cshlands.api.trip.TripClock;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
class NewExpense {
    @Setter
    private long id;
    private final long tripId;
    private final String clientUuid;
    private final String title;
    private final long amountMinor;
    private final String currency;
    private final long paidByMemberId;
    private final long createdByMemberId;
    private final SplitMode splitMode;
    private final Instant occurredAt;
    private final String occurredTz;
    private final ExpenseCategory category;
    private final Visibility visibility;
    private final Source source;
    private final String note;

    NewExpense(ExpenseRequest request, long tripId, long createdByMemberId, TripClock tripClock) {
        this.tripId = tripId;
        this.clientUuid = request.clientUuid();
        this.title = request.title();
        this.amountMinor = request.amountMinor();
        this.currency = request.currency();
        this.paidByMemberId = request.paidByMemberId();
        this.createdByMemberId = createdByMemberId;
        this.splitMode = request.splitMode();
        // TODO: 需要在 Service 解析 request 的 occurredAt 是否为真实带时区：Z 或 +09:00
        this.occurredAt = request.occurredAt() == null ? Instant.now().truncatedTo(ChronoUnit.MILLIS) : Instant.parse(request.occurredAt()).truncatedTo(ChronoUnit.MILLIS);
        this.occurredTz = request.occurredTz() == null ? tripClock.timezone() : request.occurredTz();
        this.category = request.category() == null ? ExpenseCategory.OTHER : request.category();
        this.visibility = request.splitMode() == SplitMode.NONE ? Visibility.PRIVATE : Visibility.TRIP;
        this.source = request.source() == null ? Source.FORM : request.source();
        this.note = request.note();
    }
}
