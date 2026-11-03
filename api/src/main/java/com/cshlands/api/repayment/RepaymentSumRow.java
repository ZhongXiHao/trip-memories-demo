package com.cshlands.api.repayment;

record RepaymentSumRow(
        long fromMemberId,
        long toMemberId,
        String currency,
        RepaymentStatus status,
        long amountMinor

) {
}
