package com.cshlands.api.repayment;

/**
 * 要结清的一项欠款。三个字段的非空、正数、币别是否存在、取整、同币别时实付等于销帐，
 * 都放在 RepaymentService.validate 里校验，不用 Bean Validation：
 * 嵌套校验的 fields 会带下标（items[0].xxx），而契约规定 fields 指向顶层字段名 items。
 */
record RepaymentItemRequest(
        String clearedCurrency,
        Long clearedAmountMinor,
        Long paidAmountMinor
) {
}
