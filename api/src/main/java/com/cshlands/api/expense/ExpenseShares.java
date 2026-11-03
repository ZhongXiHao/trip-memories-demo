package com.cshlands.api.expense;

import java.util.Map;

/**
 * 给还款用的一笔支出的概要：谁付的款、什么币别、是不是共同支出、每个人的分摊额。
 * 只有还款需要的这几项，不把支出的其他字段（标题、时间、类别……）暴露给别的包。
 *
 * @param shared            共同支出为 true；个人支出（不分账）为 false
 * @param shareByMemberId   分摊：memberId → 金额（×100，币别同 currency）。个人支出里只有付款人自己一行
 */
public record ExpenseShares(
        long expenseId,
        long paidByMemberId,
        String currency,
        boolean shared,
        Map<Long, Long> shareByMemberId
) {
}
