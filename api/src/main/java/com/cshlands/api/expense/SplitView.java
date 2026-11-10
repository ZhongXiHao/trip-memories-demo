package com.cshlands.api.expense;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SplitView(long memberId, long amountMinor, Long remainderMinor) {
}
