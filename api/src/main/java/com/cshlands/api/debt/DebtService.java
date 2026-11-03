package com.cshlands.api.debt;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.member.MemberService;
import com.cshlands.api.repayment.RepayableAmount;
import com.cshlands.api.repayment.RepaymentLedger;
import com.cshlands.api.repayment.RepaymentSum;
import com.cshlands.api.repayment.RepaymentSums;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
class DebtService implements RepayableAmount {
    private final RepaymentLedger repaymentLedger;
    private final DebtMapper debtMapper;
    private final MemberService memberService;

    DebtService(RepaymentLedger repaymentLedger, DebtMapper debtMapper, MemberService memberService) {
        this.repaymentLedger = repaymentLedger;
        this.debtMapper = debtMapper;
        this.memberService = memberService;
    }

    private static final Comparator<DebtView> BY_FROM_TO_CURRENCY =
            Comparator.comparingLong(DebtView::fromMemberId)
                    .thenComparingLong(DebtView::toMemberId)
                    .thenComparing(DebtView::currency);

    // 按金额降序，按 member id 升序
    private static final Comparator<SimplifiedRemainder> BY_AMOUNT_MEMBER_ID =
            Comparator.comparingLong(SimplifiedRemainder::amount).reversed()
                    .thenComparingLong(SimplifiedRemainder::memberId);


    @Transactional(readOnly = true)
    List<DebtView> overview(long userId, long tripId, Long memberId, Boolean simplify) {

        memberService.requireActiveMember(userId, tripId);

        List<DebtView> debts = netDebts(tripId);

        if (Boolean.TRUE.equals(simplify)) {
            debts = simplify(debts);
        }
        debts.sort(BY_FROM_TO_CURRENCY);

        return involving(memberId, debts);
    }

    /**
     * 当前用户和 memberId 之间的欠款，按币别分列。不支持化简：化简是整个行程的计算，限制到两个人之间会误导。
     */
    @Transactional(readOnly = true)
    List<DebtView> pair(long userId, long tripId, long memberId) {
        long myMemberId = memberService.requireActiveMember(userId, tripId).memberId();
        if (memberId == myMemberId) {
            throw ApiException.validationFailed("memberId", "must be a member other than yourself");
        }
        boolean isMemberInTrip = memberService.isMemberOfTrip(tripId, memberId);
        if(! isMemberInTrip) {
            throw ApiException.notFound();
        }

        List<DebtView> debts = netDebts(tripId);
        debts.sort(BY_FROM_TO_CURRENCY);
        return debts.stream()
                .filter(debt -> (debt.fromMemberId() == myMemberId && debt.toMemberId() == memberId)
                        || (debt.fromMemberId() == memberId && debt.toMemberId() == myMemberId))
                .toList();
    }

    /**
     * 行程里每对人、每个币别的净额。每对人最多返回一个方向，且该方向的金额为正数，表示 fromMemberId 欠 toMemberId 的金额
     */
    private List<DebtView> netDebts(long tripId) {
        List<DebtRow> debtRows = debtMapper.getDebts(tripId);
        RepaymentSums repaymentSums = repaymentLedger.sumsByStatus(tripId);
        return calculateNetAmountMinor(debtRows, repaymentSums);
    }

    private static @NonNull List<DebtView> involving(Long memberId, List<DebtView> debts) {
        return debts.stream().filter(debtView -> memberId == null || debtView.fromMemberId() == memberId || debtView.toMemberId() == memberId).toList();
    }


    private static List<DebtView> simplify(List<DebtView> debtViews) {
        // 给出一组「建议转账」：A 欠 B、B 欠 C 合并成 A 欠 C
        Map<SimplifiedKey, Long> debtsRemainder = new HashMap<>();
        debtViews.forEach(debtView -> {
            long fromMemberId = debtView.fromMemberId();
            long toMemberId = debtView.toMemberId();
            long amountMinor = debtView.amountMinor();
            String currency = debtView.currency();
            // 欠别人的
            debtsRemainder.merge(new SimplifiedKey(fromMemberId, currency), amountMinor, Long::sum);
            // 别人欠自己的
            debtsRemainder.merge(new SimplifiedKey(toMemberId, currency), -amountMinor, Long::sum);
        });

        // 遍历一次，按币别把余额分到债务人和债权人两组队列里（用最大优先队列：金额降序，金额相同 memberId 小的在前）
        Map<String, PriorityQueue<SimplifiedRemainder>> debtorsByCurrency = new HashMap<>();
        Map<String, PriorityQueue<SimplifiedRemainder>> creditorsByCurrency = new HashMap<>();
        debtsRemainder.forEach((key, remainder) -> {
            if (remainder > 0) {
                debtorsByCurrency.computeIfAbsent(key.currency(), currency -> new PriorityQueue<>(BY_AMOUNT_MEMBER_ID))
                        .add(new SimplifiedRemainder(key.memberId(), remainder));
            } else if (remainder < 0) {
                creditorsByCurrency.computeIfAbsent(key.currency(), currency -> new PriorityQueue<>(BY_AMOUNT_MEMBER_ID))
                        .add(new SimplifiedRemainder(key.memberId(), -remainder));
            }
        });

        // 同一币别里债务人的余额之和等于债权人的，所以有债务人就一定有债权人
        List<DebtView> simplified = new ArrayList<>();
        debtorsByCurrency.forEach((currency, debtors) ->
                simplified.addAll(settle(currency, debtors, creditorsByCurrency.get(currency))));
        return simplified;
    }

    // 优先让金额最大的债务人还给金额最大的债权人，直到有一边清空
    private static List<DebtView> settle(String currency, PriorityQueue<SimplifiedRemainder> debtors,
                                         PriorityQueue<SimplifiedRemainder> creditors) {
        List<DebtView> settled = new ArrayList<>();
        while (!debtors.isEmpty() && !creditors.isEmpty()) {
            SimplifiedRemainder debtor = debtors.poll();
            SimplifiedRemainder creditor = creditors.poll();
            long amount = Math.min(debtor.amount(), creditor.amount());
            settled.add(new DebtView(debtor.memberId(), creditor.memberId(), currency, amount, 0));
            if (debtor.amount() > amount) {
                debtors.add(new SimplifiedRemainder(debtor.memberId(), debtor.amount() - amount));
            }
            if (creditor.amount() > amount) {
                creditors.add(new SimplifiedRemainder(creditor.memberId(), creditor.amount() - amount));
            }
        }
        return settled;
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


    @Override
    public long maxRepayable(long tripId, long fromMemberId, long toMemberId, String currency) {
        List<DebtView> debtViews = netDebts(tripId);// 计算净额，确保 pending 也被考虑
        for (DebtView debtView : debtViews) {
            if (debtView.fromMemberId() == fromMemberId && debtView.toMemberId() == toMemberId && debtView.currency().equals(currency)) {
                return Math.max(0, debtView.amountMinor() - debtView.pendingMinor());
            }
        }
        return 0L;
    }
}
