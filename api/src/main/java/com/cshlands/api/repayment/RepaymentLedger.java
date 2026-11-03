package com.cshlands.api.repayment;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 还款对外的只读入口，给 debt 包读汇总用。只依赖 Mapper，不依赖创建还款那些类：
 * debt 的 DebtService 实现了 {@link RepayableAmount}，创建还款的类会注入它，
 * 如果 debt 依赖的是会注入创建还款的类，bean 之间就成环了。
 */
@Service
public class RepaymentLedger {
    private final RepaymentMapper repaymentMapper;

    RepaymentLedger(RepaymentMapper repaymentMapper) {
        this.repaymentMapper = repaymentMapper;
    }

    /**
     * 一条 SQL 读出来再按状态分开，所以已确认和待确认两份数据来自同一个快照
     */
    public RepaymentSums sumsByStatus(long tripId) {
        List<RepaymentSumRow> rows = repaymentMapper.getRepaymentSum(tripId);
        return new RepaymentSums(sumsOf(rows, RepaymentStatus.CONFIRMED), sumsOf(rows, RepaymentStatus.PENDING));
    }

    private static List<RepaymentSum> sumsOf(List<RepaymentSumRow> rows, RepaymentStatus status) {
        return rows.stream()
                .filter(row -> row.status() == status)
                .map(row -> new RepaymentSum(row.fromMemberId(), row.toMemberId(), row.currency(), row.amountMinor()))
                .toList();
    }
}
