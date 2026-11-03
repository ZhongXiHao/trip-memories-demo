package com.cshlands.api.debt;

record DebtRow(
        long fromMemberId,
        long toMemberId,
        String currency,
        long amountMinor
) {
}
