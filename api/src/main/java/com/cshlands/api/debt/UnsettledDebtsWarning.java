package com.cshlands.api.debt;

import com.cshlands.api.common.Warning;

import java.util.List;

public record UnsettledDebtsWarning(
        String code,
        String message,
        List<DebtView> debts
) implements Warning {

}
