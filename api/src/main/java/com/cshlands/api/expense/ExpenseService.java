package com.cshlands.api.expense;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.common.ErrorCode;
import com.cshlands.api.currency.CurrencyMapper;
import com.cshlands.api.currency.CurrencyView;
import com.cshlands.api.member.ActiveMember;
import com.cshlands.api.member.MemberService;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
class ExpenseService {
    private final CurrencyMapper currencyMapper;
    private final MemberService memberService;

    ExpenseService(CurrencyMapper currencyMapper, MemberService memberService) {
        this.currencyMapper = currencyMapper;
        this.memberService = memberService;
    }

    /**
     * 只算不写。校验规则与创建相同（币别、取整单位、成员、分摊方式），返回算好的分摊。
     */
    ExpensePreview preview(long userId, long tripId, ExpenseRequest request) {
        List<SplitView> splits = validateAndSplit(userId, tripId, request);
        return new ExpensePreview(request.amountMinor(), request.currency(), splits, List.of());
    }

    private @NonNull List<SplitView> validateAndSplit(long userId, long tripId, ExpenseRequest request) {
        ActiveMember me = memberService.requireActiveMember(userId, tripId);

        int roundingUnit = requireRoundingUnit(request.currency());
        requireRoundable(request.amountMinor(), roundingUnit);
        requireValidMembers(tripId, request);

        return switch (request.splitMode()) {
            case NONE -> personalSplit(me.memberId(), request);
            case EQUAL -> equalSplits(request, roundingUnit);
            case CUSTOM -> customSplits(request, roundingUnit);
        };
    }

    // ---- 三种分摊方式 ----

    /**
     * 个人支出：不带 splits，付款人必须是自己，一条分摊 = 总额
     */
    private static List<SplitView> personalSplit(long currentMemberId, ExpenseRequest request) {
        if (hasSplits(request)) {
            throw ApiException.validationFailed("splits", "must be null or empty when splitMode is NONE");
        }
        if (request.paidByMemberId() != currentMemberId) {
            throw ApiException.validationFailed("paidByMemberId", "must be the current user when splitMode is NONE");
        }
        return List.of(new SplitView(request.paidByMemberId(), request.amountMinor(), null));
    }

    /**
     * 平均分：余数一次性给 {@link #remainderHolderId} 选出的那个人，其余人拿整份
     */
    private static List<SplitView> equalSplits(ExpenseRequest request, int roundingUnit) {
        List<SplitRequest> members = requireSplits(request);
        long totalMinor = request.amountMinor();
        long remainderMinor = totalMinor % ((long) roundingUnit * members.size());
        long shareMinor = (totalMinor - remainderMinor) / members.size();
        long remainderHolderId = remainderHolderId(request.paidByMemberId(), members);

        List<SplitView> splits = new ArrayList<>(members.size());
        for (SplitRequest member : members) {
            boolean holdsRemainder = remainderMinor > 0 && member.memberId() == remainderHolderId;
            splits.add(holdsRemainder
                    ? new SplitView(member.memberId(), shareMinor + remainderMinor, remainderMinor)
                    : new SplitView(member.memberId(), shareMinor, null));
        }
        return splits;
    }

    /**
     * 余数给付款人；付款人不在名单里则给名单第一位（见契约「记账预览」）
     */
    private static long remainderHolderId(long payerId, List<SplitRequest> members) {
        boolean payerIsListed = members.stream().anyMatch(member -> member.memberId() == payerId);
        return payerIsListed ? payerId : members.get(0).memberId();
    }

    /**
     * 自定义：先逐项校验，再校验之和等于总额，这样错误码不取决于金额的顺序
     */
    private static List<SplitView> customSplits(ExpenseRequest request, int roundingUnit) {
        List<SplitView> splits = new ArrayList<>();
        for (SplitRequest split : requireSplits(request)) {
            Long amountMinor = split.amountMinor();
            if (amountMinor == null) {
                throw ApiException.validationFailed("splits", "amount must not be null");
            }
            if (amountMinor < 0) {
                throw ApiException.validationFailed("splits", "amount must not be negative");
            }
            requireRoundable(amountMinor, roundingUnit);
            splits.add(new SplitView(split.memberId(), amountMinor, null));
        }
        requireSumEquals(request.amountMinor(), splits);
        return splits;
    }

    /**
     * 用总额往下减而不是累加：每项都 >= 0，减到负数就说明超了，不会溢出
     */
    private static void requireSumEquals(long totalMinor, List<SplitView> splits) {
        long remainingMinor = totalMinor;
        for (SplitView split : splits) {
            remainingMinor -= split.amountMinor();
            if (remainingMinor < 0) {
                break;
            }
        }
        if (remainingMinor != 0) {
            throw splitSumMismatch();
        }
    }

    // ---- 校验 ----

    private int requireRoundingUnit(String currencyCode) {
        CurrencyView currency = currencyMapper.find(currencyCode);
        if (currency == null) {
            throw ApiException.validationFailed("currency", "unsupported currency");
        }
        return currency.roundingUnit();
    }

    private static void requireRoundable(long amountMinor, int roundingUnit) {
        if (amountMinor % roundingUnit != 0) {
            // 当单位是 1 时，只允许保留小数后两位
            String message = roundingUnit == 100 ? "Amount must be an integer" : "Amount must be 2 decimal places";
            throw ApiException.of(ErrorCode.AMOUNT_NOT_ROUNDABLE, message);
        }
    }

    /**
     * 付款人和所有分摊成员：名单里不能有重复，且都必须是这个行程的在籍成员
     */
    private void requireValidMembers(long tripId, ExpenseRequest request) {
        List<SplitRequest> splits = request.splits() == null ? List.of() : request.splits();
        requireNoDuplicateMembers(splits);

        // 付款人排在最前，先检查
        List<Long> memberIds = new ArrayList<>();
        memberIds.add(request.paidByMemberId());
        splits.forEach(split -> memberIds.add(split.memberId()));
        memberService.requireActiveMembers(tripId, memberIds);
    }

    /**
     * 只看请求本身，放在查库之前
     */
    private static void requireNoDuplicateMembers(List<SplitRequest> splits) {
        Set<Long> seen = new HashSet<>();
        for (SplitRequest split : splits) {
            if (!seen.add(split.memberId())) {
                throw ApiException.validationFailed("splits", "duplicate memberId " + split.memberId());
            }
        }
    }

    private static boolean hasSplits(ExpenseRequest request) {
        return request.splits() != null && !request.splits().isEmpty();
    }

    /**
     * equal 和 custom 必须带 splits
     */
    private static List<SplitRequest> requireSplits(ExpenseRequest request) {
        if (!hasSplits(request)) {
            throw ApiException.validationFailed("splits", "must not be null or empty when splitMode is " + request.splitMode());
        }
        return request.splits();
    }

    private static ApiException splitSumMismatch() {
        return ApiException.of(ErrorCode.SPLIT_SUM_MISMATCH, "The sum of custom splits does not equal the total amount");
    }
}
