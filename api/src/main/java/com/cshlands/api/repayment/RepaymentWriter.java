package com.cshlands.api.repayment;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.common.ErrorCode;
import com.cshlands.api.expense.ExpenseLookup;
import com.cshlands.api.expense.ExpenseShares;
import com.cshlands.api.member.MemberService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 创建还款的写库部分，单独一个 bean：同一个类里的 this.write() 不经过事务代理（同 ExpenseWriter）。
 * 额度检查在这里做，因为它必须在事务里、先锁住两个成员行之后读数据，见 {@link #write}。
 */
@Component
class RepaymentWriter {
    private final RepaymentMapper repaymentMapper;
    private final MemberService memberService;
    private final RepayableAmount repayableAmount;
    private final ExpenseLookup expenseLookup;

    RepaymentWriter(RepaymentMapper repaymentMapper, MemberService memberService, RepayableAmount repayableAmount, ExpenseLookup expenseLookup) {
        this.repaymentMapper = repaymentMapper;
        this.memberService = memberService;
        this.repayableAmount = repayableAmount;
        this.expenseLookup = expenseLookup;
    }

    /**
     * 一个组的所有行要么全写进去，要么一行都没有。返回 false 表示这个组已经存在（别的请求抢先写入了），
     * 调用方回到开头重新读，走重放那条路。
     * <p>
     * 顺序不能变：
     * <ol>
     *   <li>先锁两个成员行。同一对人的创建在这里排队，两个各自没超额、合起来超额的请求只会有一个通过；</li>
     *   <li>锁到之后再读数据。REPEATABLE READ 下事务里第一次普通读之后快照就固定了，加锁（当前读）必须在它之前；</li>
     *   <li>先看组是否已存在，再检查额度：否则同一个 clientUuid 的第二个请求，会因为第一个请求已经占了额度而得到
     *       REPAYMENT_EXCEEDS_DEBT，而不是 200 的重放；</li>
     *   <li>最后一条语句插入整个组。唯一键 (trip_id, settlement_group_id, cleared_currency) 是兜底：
     *       涉及不同成员对、但用了同一个 clientUuid 的两个请求不会互相排队，由它裁决。</li>
     * </ol>
     */
    @Transactional
    boolean write(long tripId, long fromMemberId, long toMemberId, List<NewRepayment> rows) {
        memberService.lockMembers(tripId, fromMemberId, toMemberId);

        String settlementGroupId = rows.get(0).settlementGroupId();
        if (!repaymentMapper.findByGroup(tripId, settlementGroupId).isEmpty()) {
            return false;
        }

        checkLimits(tripId, fromMemberId, toMemberId, rows);

        try {
            repaymentMapper.insertAll(rows);
        } catch (DuplicateKeyException e) {
            return false;
        }
        return true;
    }

    /**
     * 每一项的 clearedAmountMinor 不能超过 repayableAmount.maxRepayable(tripId, from, to, clearedCurrency)，
     * 否则抛 ApiException.of(ErrorCode.REPAYMENT_EXCEEDS_DEBT, ...)。没有欠款、欠款在反方向，maxRepayable 都返回 0，同样报这个错。
     * 带 expenseId 时还有这笔支出的限额（见契约「针对某笔支出还款」）：from 在这笔里的分摊额，
     * 减去这笔上 from 已有的、没取消的还款（pending 和 confirmed）的销帐金额，和上面的额度取小。
     * 任何一项超额，整个请求失败，什么都不写。这里在事务里，抛异常即整体回滚。
     */
    private void checkLimits(long tripId, long fromMemberId, long toMemberId, List<NewRepayment> rows) {
        for (NewRepayment row : rows) {
            long maxRepayable = repayableAmount.maxRepayable(tripId, fromMemberId, toMemberId, row.clearedCurrency());
            Long expenseId = row.expenseId();
            if (expenseId != null) {
                ExpenseShares visible = expenseLookup.findVisible(tripId, expenseId, row.createdByMemberId());
                if(visible == null){
                    throw ApiException.notFound();
                }
                long expenseSplitDebt = visible.shareByMemberId().getOrDefault(fromMemberId, 0L);
                long sumClearedForExpense = repaymentMapper.sumClearedForExpense(tripId, expenseId, fromMemberId);
                // Expense 的限额是分摊额减去已还款额，和上面的可还款额度取小，但最终不能小于 0（没有欠款、欠款在反方向，maxRepayable 都返回 0）。
                maxRepayable = Math.min(maxRepayable, expenseSplitDebt - sumClearedForExpense);
                maxRepayable = Math.max(0, maxRepayable);
            }
            if (row.clearedAmountMinor() > maxRepayable) {
                throw ApiException.of(ErrorCode.REPAYMENT_EXCEEDS_DEBT, "Repayment exceeds debt for clearedCurrency " + row.clearedCurrency() + " from member " + fromMemberId + " to member " + toMemberId);
            }
        }
    }
}
