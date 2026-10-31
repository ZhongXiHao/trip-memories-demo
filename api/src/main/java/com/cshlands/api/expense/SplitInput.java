package com.cshlands.api.expense;

import java.util.List;

/**
 * 预览、创建、修改的请求体共有的、算分摊要用的那部分
 */
interface SplitInput {
    Long amountMinor();

    String currency();

    Long paidByMemberId();

    SplitMode splitMode();

    List<SplitRequest> splits();
}
