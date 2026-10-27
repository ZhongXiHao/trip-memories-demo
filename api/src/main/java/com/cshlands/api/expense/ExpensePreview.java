package com.cshlands.api.expense;

import com.cshlands.api.common.Warning;

import java.util.List;

record ExpensePreview(
        long amountMinor,
        String currency,
        List<SplitView> splits,
        List<Warning> warnings
) {}