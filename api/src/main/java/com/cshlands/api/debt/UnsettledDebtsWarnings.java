package com.cshlands.api.debt;

import com.cshlands.api.common.Warning;
import com.cshlands.api.member.LeaveWarnings;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
class UnsettledDebtsWarnings implements LeaveWarnings {
    private final DebtService debtService;

    UnsettledDebtsWarnings(DebtService debtService) {
        this.debtService = debtService;
    }

    @Override
    public List<Warning> of(long tripId, long memberId) {
        List<DebtView> debts = debtService.debtsInvolving(tripId, memberId);
        if (debts.isEmpty()) {
            return List.of();
        }
        return List.of(new UnsettledDebtsWarning("UNSETTLED_DEBTS", "The member has unsettled debts.", debts));

    }
}
