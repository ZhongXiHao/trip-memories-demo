package com.cshlands.api.repayment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

record CreateRepaymentRequest(
        @NotBlank String clientUuid,
        @NotNull Long fromMemberId,
        @NotNull Long toMemberId,
        @NotBlank String paidCurrency,
        @NotEmpty List<RepaymentItemRequest> items,
        Long expenseId,
        @Size(max = 512, message = "Note must be at most 512 characters long") String note
) {
}
