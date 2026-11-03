package com.cshlands.api.repayment;

public interface RepayableAmount {
    long maxRepayable(long tripId, long fromMemberId, long toMemberId, String currency);
}
