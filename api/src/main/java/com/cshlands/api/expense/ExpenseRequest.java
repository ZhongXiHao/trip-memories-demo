package com.cshlands.api.expense;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

record ExpenseRequest(
        String clientUuid,
        Long expenseId, // 仅预览用：修改某笔支出前预览时带上，创建时传了也忽略
        @NotBlank @Size(max = 128, message = "Title must be at most 128 characters long") String title,
        @NotNull @Positive Long amountMinor,
        @NotBlank String currency,
        @NotNull Long paidByMemberId,
        @NotNull SplitMode splitMode,
        List<SplitRequest> splits,
        String occurredAt,
        String occurredTz,
        ExpenseCategory category,
        @Size(max = 512, message = "Notes must be at most 512 characters long") String note,
        Source source,
        Integer version

) {

}
