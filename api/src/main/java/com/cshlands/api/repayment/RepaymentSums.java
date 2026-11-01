package com.cshlands.api.repayment;

import java.util.List;

/** 行程里没取消的还款，按状态分开汇总：已确认的抵销欠款，待确认的另列 */
public record RepaymentSums(
        List<RepaymentSum> confirmed,
        List<RepaymentSum> pending
) {
}
