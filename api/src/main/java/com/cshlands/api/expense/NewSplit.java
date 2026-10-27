package com.cshlands.api.expense;


record NewSplit(
        long tripId,
        long expenseId,
        long memberId,
        long amountMinor
) {
}
