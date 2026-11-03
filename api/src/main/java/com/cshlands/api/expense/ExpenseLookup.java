package com.cshlands.api.expense;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * expense 包对外的只读入口，给还款查"针对某笔支出还款"要用的信息；
 * 其他包只通过它读支出，不直接碰 ExpenseMapper（同 MemberService）。
 */
@Service
public class ExpenseLookup {
    private final ExpenseMapper expenseMapper;

    ExpenseLookup(ExpenseMapper expenseMapper) {
        this.expenseMapper = expenseMapper;
    }

    /**
     * 这个成员看得见的、没删除的一笔支出：共同支出，或者自己记的个人支出。
     * 不存在、已删除、别的行程的、别人的个人支出，一律返回 null，调用方都当 404，不泄露它存在过。
     * viewerMemberId 是 memberId，不是 userId。
     */
    public ExpenseShares findVisible(long tripId, long expenseId, long viewerMemberId) {
        ExpenseRow row = expenseMapper.findVisible(tripId, expenseId, viewerMemberId);
        if (row == null) {
            return null;
        }
        Map<Long, Long> shareByMemberId = expenseMapper.findSplits(expenseId).stream()
                .collect(Collectors.toMap(SplitRow::memberId, SplitRow::amountMinor));
        return new ExpenseShares(row.id(), row.paidByMemberId(), row.currency(),
                row.splitMode() != SplitMode.NONE, shareByMemberId);
    }
}
