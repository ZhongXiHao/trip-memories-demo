package com.cshlands.api.repayment;

record RepaymentListQuery(
        long tripId,
        RepaymentStatus status,
        Long memberId,
        String settlementGroupId
) {
}
