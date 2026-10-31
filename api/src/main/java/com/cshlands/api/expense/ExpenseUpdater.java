package com.cshlands.api.expense;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
class ExpenseUpdater {
    private final ExpenseMapper expenseMapper;

    ExpenseUpdater(ExpenseMapper expenseMapper) {
        this.expenseMapper = expenseMapper;
    }

    @Transactional
    boolean update(UpdateExpense updateExpense, List<NewSplit> newSplits) {
        int rows = expenseMapper.update(updateExpense);
        if (rows < 1) {
            return false;
        }
        expenseMapper.deleteSplits(updateExpense.tripId(), updateExpense.expenseId());
        expenseMapper.insertSplits(newSplits);
        return true;
    }
}
