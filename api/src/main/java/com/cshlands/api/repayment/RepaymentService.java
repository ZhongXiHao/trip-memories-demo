package com.cshlands.api.repayment;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RepaymentService {
    private final RepaymentMapper repaymentMapper;

    RepaymentService(RepaymentMapper repaymentMapper) {
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
