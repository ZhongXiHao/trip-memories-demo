package com.cshlands.api.debt;

record PairWithCurrency(long lowMemberId, long highMemberId, String currency) {
    static PairWithCurrency of(long memberA, long memberB, String currency) {
        return new PairWithCurrency(Math.min(memberA, memberB), Math.max(memberA, memberB), currency);
    }
}
