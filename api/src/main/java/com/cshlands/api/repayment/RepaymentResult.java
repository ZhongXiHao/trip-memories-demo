package com.cshlands.api.repayment;

/** Service 返回给 Controller：首次创建 201，同一个 clientUuid 的重放 200 */
record RepaymentResult(RepaymentGroupView group, boolean created) {
}
