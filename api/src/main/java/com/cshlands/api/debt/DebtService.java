package com.cshlands.api.debt;

import com.cshlands.api.member.MemberService;
import com.cshlands.api.repayment.RepaymentService;
import com.cshlands.api.repayment.RepaymentSum;
import com.cshlands.api.repayment.RepaymentSums;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
class DebtService {
    private final RepaymentService repaymentService;
    private final DebtMapper debtMapper;
    private final MemberService memberService;

    DebtService(RepaymentService repaymentService, DebtMapper debtMapper, MemberService memberService) {
        this.repaymentService = repaymentService;
        this.debtMapper = debtMapper;
        this.memberService = memberService;
    }

    private static final Comparator<DebtView> BY_FROM_TO_CURRENCY =
            Comparator.comparingLong(DebtView::fromMemberId)
                    .thenComparingLong(DebtView::toMemberId)
                    .thenComparing(DebtView::currency);

    @Transactional(readOnly = true)
    List<DebtView> overview(long userId, long tripId, Long memberId) {
        memberService.requireActiveMember(userId, tripId);

        List<DebtRow> debtRows = debtMapper.getDebts(tripId);
        RepaymentSums repaymentSums = repaymentService.sumsByStatus(tripId);
        // 每对人，每个币种最多返回一个方向，且该方向的金额为正数，表示 fromMemberId 欠 toMemberId 的金额
        List<DebtView> debtViews = calculateNetAmountMinor(debtRows, repaymentSums);
        debtViews.sort(BY_FROM_TO_CURRENCY);
        return debtViews.stream().filter(debtView -> memberId == null || debtView.fromMemberId() == memberId || debtView.toMemberId() == memberId).toList();
    }

    private static List<DebtView> calculateNetAmountMinor(List<DebtRow> debtRows, RepaymentSums repaymentSums) {
        // 每对人，每个币种最多返回一个方向，且该方向的金额为正数，表示 fromMemberId 欠 toMemberId 的金额
        List<DebtView> debtViews = new ArrayList<>();
        // 累加用 Map<PairWithCurrency, Long> 加 merge(key, 带符号金额, Long::sum)，不用 groupingBy
        Map<PairWithCurrency, Long> netAmountMinorMap = netByPair(debtRows, repaymentSums.confirmed());
        Map<DirectedPair, Long> pendingMinorByDirection = pendingByDirection(repaymentSums.pending());
        netAmountMinorMap.forEach((pairWithCurrency, amountMinor) -> {
            if (amountMinor == 0L) {
                return;
            }
            long fromMemberId = amountMinor > 0 ? pairWithCurrency.lowMemberId() : pairWithCurrency.highMemberId();
            long toMemberId = amountMinor > 0 ? pairWithCurrency.highMemberId() : pairWithCurrency.lowMemberId();
            // 只找当前方向的待确认金额，反方向的待确认金额不算在内
            long pendingMinor = pendingMinorByDirection.getOrDefault(
                    new DirectedPair(fromMemberId, toMemberId, pairWithCurrency.currency()), 0L);
            debtViews.add(new DebtView(fromMemberId, toMemberId, pairWithCurrency.currency(), Math.abs(amountMinor), pendingMinor));
        });

        return debtViews;
    }

    /**
     * 待确认的还款按方向汇总：A→B 和 B→A 是两个键，不能像净额那样合并
     */
    private static Map<DirectedPair, Long> pendingByDirection(List<RepaymentSum> repaymentSumsPending) {
        Map<DirectedPair, Long> pendingMinorByDirection = new HashMap<>();
        repaymentSumsPending.forEach(repaymentSum -> pendingMinorByDirection.merge(
                new DirectedPair(repaymentSum.fromMemberId(), repaymentSum.toMemberId(), repaymentSum.currency()),
                repaymentSum.amountMinor(), Long::sum));
        return pendingMinorByDirection;
    }

    private static Map<PairWithCurrency, Long> netByPair(List<DebtRow> debtRows, List<RepaymentSum> repaymentSumsConfirmed) {
        Map<PairWithCurrency, Long> netAmountMinorMap = new HashMap<>();
        debtRows.forEach(debtRow -> {
            long amount = debtRow.fromMemberId() < debtRow.toMemberId() ? debtRow.amountMinor() : -debtRow.amountMinor();
            netAmountMinorMap.merge(PairWithCurrency.of(debtRow.fromMemberId(), debtRow.toMemberId(), debtRow.currency()), amount, Long::sum);
        });

        repaymentSumsConfirmed.forEach(repaymentSum -> {
            long amount = repaymentSum.fromMemberId() > repaymentSum.toMemberId() ? repaymentSum.amountMinor() : -repaymentSum.amountMinor();
            netAmountMinorMap.merge(PairWithCurrency.of(repaymentSum.fromMemberId(), repaymentSum.toMemberId(), repaymentSum.currency()), amount, Long::sum);
        });
        return netAmountMinorMap;
    }


}
