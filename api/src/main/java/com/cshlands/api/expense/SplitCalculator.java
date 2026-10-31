package com.cshlands.api.expense;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.common.ErrorCode;

import java.util.ArrayList;
import java.util.List;

import static com.cshlands.api.common.ApiException.validationFailed;

/**
 * 分摊计算：把请求里的总额、币别取整单位和成员，算成每个人的分摊。
 * 只看请求本身，不查数据库，不依赖 Spring，所以可以直接单元测试。
 */
final class SplitCalculator {

    private SplitCalculator() {
    }

    /**
     * 按分摊方式算出每个人的分摊。不查成员，只看请求本身。
     * currentMemberId 是当前操作的成员：个人支出的付款人必须是他。
     */
    static List<SplitView> split(long currentMemberId, SplitInput request, int roundingUnit) {
        return switch (request.splitMode()) {
            case NONE -> personalSplit(currentMemberId, request);
            case EQUAL -> equalSplits(request, roundingUnit);
            case CUSTOM -> customSplits(request, roundingUnit);
        };
    }

    // ---- 三种分摊方式 ----

    /**
     * 个人支出：不带 splits，付款人必须是自己，一条分摊 = 总额
     */
    private static List<SplitView> personalSplit(long currentMemberId, SplitInput request) {
        if (hasSplits(request)) {
            throw validationFailed("splits", "must be null or empty when splitMode is NONE");
        }
        if (request.paidByMemberId() != currentMemberId) {
            throw validationFailed("paidByMemberId", "must be the current user when splitMode is NONE");
        }
        return List.of(new SplitView(request.paidByMemberId(), request.amountMinor(), null));
    }

    /**
     * 平均分：余数一次性给 {@link #remainderHolderId} 选出的那个人，其余人拿整份
     */
    private static List<SplitView> equalSplits(SplitInput request, int roundingUnit) {
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
    private static List<SplitView> customSplits(SplitInput request, int roundingUnit) {
        List<SplitView> splits = new ArrayList<>();
        for (SplitRequest split : requireSplits(request)) {
            Long amountMinor = split.amountMinor();
            if (amountMinor == null) {
                throw validationFailed("splits", "amount must not be null");
            }
            if (amountMinor < 0) {
                throw validationFailed("splits", "amount must not be negative");
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

    // ---- 取整和必填检查 ----

    static void requireRoundable(long amountMinor, int roundingUnit) {
        if (amountMinor % roundingUnit != 0) {
            // 当单位是 1 时，只允许保留小数后两位
            String message = roundingUnit == 100 ? "Amount must be an integer" : "Amount must be 2 decimal places";
            throw ApiException.of(ErrorCode.AMOUNT_NOT_ROUNDABLE, message);
        }
    }

    private static boolean hasSplits(SplitInput request) {
        return request.splits() != null && !request.splits().isEmpty();
    }

    /**
     * equal 和 custom 必须带 splits
     */
    private static List<SplitRequest> requireSplits(SplitInput request) {
        if (!hasSplits(request)) {
            throw validationFailed("splits", "must not be null or empty when splitMode is " + request.splitMode());
        }
        return request.splits();
    }

    private static ApiException splitSumMismatch() {
        return ApiException.of(ErrorCode.SPLIT_SUM_MISMATCH, "The sum of custom splits does not equal the total amount");
    }
}
