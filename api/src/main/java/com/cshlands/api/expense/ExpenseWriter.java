package com.cshlands.api.expense;

import com.cshlands.api.trip.TripClock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Component
class ExpenseWriter {
    private final ExpenseMapper expenseMapper;

    ExpenseWriter(ExpenseMapper expenseMapper) {
        this.expenseMapper = expenseMapper;
    }

    @Transactional
    boolean write(long tripId, long createdByMemberId, TripClock tripClock, ExpenseRequest request, List<SplitView> splits) {
        NewExpense newExpense = new NewExpense(request, tripId, createdByMemberId, tripClock);
        try {
            expenseMapper.insert(newExpense);
        } catch (DuplicateKeyException e) {
            return false;
        }

        try {
            List<NewSplit> newSplits = splits.stream().map(split -> new NewSplit(tripId, newExpense.getId(), split.memberId(), split.amountMinor())).collect(Collectors.toList());
            expenseMapper.insertSplits(newSplits);
        } catch (DuplicateKeyException e) {
            //expense_split 的唯一键是 (expense_id, trip_id, member_id)，请求里重复成员在 validate 已经挡掉，所以这个分支基本不会触发
            throw new IllegalStateException("duplicate split for expense " + newExpense.getId());
        }

        return true;
    }
}
