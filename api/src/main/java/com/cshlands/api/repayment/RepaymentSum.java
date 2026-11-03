package com.cshlands.api.repayment;

/** 一对人在一个币别下的还款销帐金额之和，给 debt 包用；不带状态，状态由 {@link RepaymentSums} 的两个列表区分 */
public record RepaymentSum(
        long fromMemberId,
        long toMemberId,
        String currency,
        long amountMinor
) {
}
