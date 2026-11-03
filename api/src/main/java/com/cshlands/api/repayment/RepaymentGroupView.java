package com.cshlands.api.repayment;

import java.util.List;

/** 创建的响应：一次转账（一个 settlementGroupId）里的所有还款，按 clearedCurrency 升序 */
record RepaymentGroupView(
        String settlementGroupId,
        List<RepaymentView> items
) {
}
