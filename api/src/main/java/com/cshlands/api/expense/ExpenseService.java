package com.cshlands.api.expense;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.currency.CurrencyMapper;
import com.cshlands.api.currency.CurrencyView;
import com.cshlands.api.member.ActiveMember;
import com.cshlands.api.member.MemberService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
class ExpenseService {
    private final CurrencyMapper currencyMapper;
    private final MemberService memberService;

    ExpenseService(CurrencyMapper currencyMapper, MemberService memberService) {
        this.currencyMapper = currencyMapper;
        this.memberService = memberService;
    }

    ExpensePreview checkPreview(long userId, long tripId, ExpenseRequest request) {
        ActiveMember me = memberService.requireActiveMember(userId, tripId);

        CurrencyView currency = currencyMapper.find(request.currency());
        if (currency == null) {
            throw ApiException.validationFailed("currency", "unsupported currency");
        }
        int roundingUnit = currency.roundingUnit();
        validateAmountMinor(request.amountMinor(), roundingUnit);
        List<SplitView> splitViews = calculateSplits(me.memberId(), tripId, request, roundingUnit);
        return new ExpensePreview(request.amountMinor(), request.currency(), splitViews, List.of());
    }

    private void validateAmountMinor(long amountMinor, int roundingUnit) {
        if (!(amountMinor % roundingUnit == 0)) {
            // 当单位是 1 时，只允许保留小数后两位
            String message = roundingUnit == 100 ? "Amount must be an integer" : "Amount must be 2 decimal places";
            throw new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_NOT_ROUNDABLE", message);
        }
    }

    private List<SplitView> calculateSplits(long currentMemberId, long tripId, ExpenseRequest request, int roundingUnit) {
        List<SplitRequest> splitRequests = request.splits();
        List<SplitView> splitViews = new ArrayList<>(splitRequests == null ? 1 : splitRequests.size());
        Long paidByMemberId = request.paidByMemberId();
        validateMembers(paidByMemberId, tripId, splitRequests);

        SplitMode splitMode = request.splitMode();
        switch (splitMode) {
            case NONE -> {
                if (splitRequests != null && !splitRequests.isEmpty()) {
                    throw ApiException.validationFailed("splits", "must be null or empty when splitMode is NONE");
                }
                if (currentMemberId != paidByMemberId) {
                    throw ApiException.validationFailed("paidByMemberId", "must be the current user when splitMode is NONE");
                }
                splitViews.add(new SplitView(paidByMemberId, request.amountMinor(), null));
            }
            case EQUAL -> {
                if (splitRequests == null || splitRequests.isEmpty()) {
                    throw ApiException.validationFailed("splits", "must not be null or empty when splitMode is EQUAL");
                }
                splitEqually(request, roundingUnit, splitViews);
            }
            case CUSTOM -> {
                if (splitRequests == null || splitRequests.isEmpty()) {
                    throw ApiException.validationFailed("splits", "must not be null or empty when splitMode is CUSTOM");
                }
                // 先逐项校验，再校验之和等于总额，这样错误码不取决于金额的顺序
                for (SplitRequest split : splitRequests) {
                    if (split.amountMinor() == null) {
                        throw ApiException.validationFailed("splits", "amount must not be null");
                    }
                    if (split.amountMinor() < 0) {
                        throw ApiException.validationFailed("splits", "amount must not be negative");
                    }
                    validateAmountMinor(split.amountMinor(), roundingUnit);
                    splitViews.add(new SplitView(split.memberId(), split.amountMinor(), null));
                }
                // 用总额往下减而不是累加：每项都 >= 0，减到负数就说明超了，不会溢出
                long remaining = request.amountMinor();
                for (SplitView view : splitViews) {
                    remaining -= view.amountMinor();
                    if (remaining < 0) {
                        break;
                    }
                }
                if (remaining != 0) {
                    throw splitSumMismatch();
                }
            }

        }
        return splitViews;
    }

    private static ApiException splitSumMismatch() {
        return new ApiException(HttpStatus.BAD_REQUEST, "SPLIT_SUM_MISMATCH", "The sum of custom splits does not equal the total amount");
    }

    private void splitEqually(ExpenseRequest request, long roundingUnit, List<SplitView> splitViews) {
        long totalAmount = request.amountMinor();
        List<SplitRequest> splitRequests = request.splits();
        long remainder = totalAmount % (roundingUnit * splitRequests.size());
        long equalAmount = (totalAmount - remainder) / splitRequests.size();

        Long paidByMemberId = request.paidByMemberId();
        if (remainder == 0) {
            splitRequests.forEach(splitRequest -> {
                splitViews.add(new SplitView(splitRequest.memberId(), equalAmount, null));
            });
        } else {
            Set<Long> collect = splitRequests.stream().map(SplitRequest::memberId).collect(Collectors.toSet());
            long remainderPaidByMemberId = collect.contains(paidByMemberId) ? paidByMemberId : splitRequests.get(0).memberId();
            splitRequests.forEach(splitRequest -> {
                boolean flag = remainderPaidByMemberId == splitRequest.memberId();
                splitViews.add(new SplitView(splitRequest.memberId(), flag ? equalAmount + remainder : equalAmount, flag ? remainder : null));
            });
        }
    }

    private void validateMembers(long paidByMemberId, long tripId, List<SplitRequest> splitRequests) {
        // 检查分摊成员是否有重复：只看请求本身，放在查库之前
        if (splitRequests != null) {
            Set<Long> seen = new HashSet<>();
            for (SplitRequest s : splitRequests) {
                if (!seen.add(s.memberId())) {
                    throw ApiException.validationFailed("splits", "duplicate memberId " + s.memberId());
                }
            }
        }

        // 付款人和所有分摊成员都必须是当前行程的在籍成员；付款人排在最前，先检查
        List<Long> memberIds = new ArrayList<>();
        memberIds.add(paidByMemberId);
        if (splitRequests != null) {
            splitRequests.forEach(splitRequest -> memberIds.add(splitRequest.memberId()));
        }
        memberService.requireActiveMembers(tripId, memberIds);
    }
}
