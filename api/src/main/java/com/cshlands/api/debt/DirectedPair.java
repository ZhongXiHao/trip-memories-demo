package com.cshlands.api.debt;

/** 有方向的键：fromMemberId 付给 toMemberId，某个币别。和 PairWithCurrency 不同，这里 (A,B) 与 (B,A) 是两个键 */
record DirectedPair(long fromMemberId, long toMemberId, String currency) {
}
