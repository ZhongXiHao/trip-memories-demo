package com.cshlands.api.expense;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * expense 包对外的记账预览入口，给规则指令把解析出来的草稿算成预览（同 ExpenseLookup，别的包不直接碰 ExpenseService）。
 */
@Service
public class ExpensePreviewer {
    private final ExpenseService expenseService;

    ExpensePreviewer(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    /**
     * 校验和计算与 POST /expenses/preview 完全相同，失败时抛同样的 ApiException。
     * 只给完整的草稿用：请求体上的 @Valid 在这里不经过，必填字段由调用方保证（缺了直接抛 NullPointerException，是调用方的 bug）。
     */
    public ExpensePreview preview(long userId, long tripId, ExpenseDraft draft) {
        Objects.requireNonNull(draft.title(), "title");
        Objects.requireNonNull(draft.amountMinor(), "amountMinor");
        Objects.requireNonNull(draft.currency(), "currency");
        Objects.requireNonNull(draft.paidByMemberId(), "paidByMemberId");
        Objects.requireNonNull(draft.splitMode(), "splitMode");

        List<SplitRequest> splits = draft.splits() == null ? null
                : draft.splits().stream().map(split -> new SplitRequest(split.memberId(), null)).toList();
        ExpenseRequest request = new ExpenseRequest(null, null, draft.title(), draft.amountMinor(), draft.currency(),
                draft.paidByMemberId(), draft.splitMode(), splits, draft.occurredAt(), draft.occurredTz(),
                null, null, draft.source());
        return expenseService.preview(userId, tripId, request);
    }
}
