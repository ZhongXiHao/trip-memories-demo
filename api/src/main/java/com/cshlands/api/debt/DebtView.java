package com.cshlands.api.debt;

record DebtView(
        long fromMemberId,
        long toMemberId,
        String currency,
        long amountMinor,
        long pendingMinor
) {
}
