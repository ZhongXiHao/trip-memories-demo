package com.cshlands.api.repayment;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.common.ErrorCode;
import com.cshlands.api.member.MemberService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 确认和取消待确认的还款，单条和整组。创建（RepaymentService）和列表（RepaymentQueryService）是别的用例。
 * <p>
 * 确认和取消都由一条带条件的 UPDATE 裁决（条件里有 {@code status = 'pending' AND deleted_at IS NULL}，
 * 同时 {@code version = version + 1}），不先查再改。影响 0 行不一定是失败（可能早就确认或取消过了），
 * 所以更新前后各读一次再判断。确认和取消同时发生时只有先成功的那个生效。
 */
@Service
class RepaymentStatusService {
    private final MemberService memberService;
    private final RepaymentMapper repaymentMapper;

    RepaymentStatusService(MemberService memberService, RepaymentMapper repaymentMapper) {
        this.memberService = memberService;
        this.repaymentMapper = repaymentMapper;
    }

    /**
     * <ul>
     *   <li>不是在籍成员 404（包括已退出的收款方）；这条还款不在本行程里、不存在、已取消 404；</li>
     *   <li>在籍但不是收款方（包括付款方、Owner）403；</li>
     *   <li>已经 confirmed 的重复确认 200，什么都不改（version 不变）；</li>
     *   <li>不检查额度；付款方已退出不影响收款方确认。</li>
     * </ul>
     */
    RepaymentView confirm(long userId, long tripId, long repaymentId) {
        long meMemberId = memberService.requireActiveMember(userId, tripId).memberId();
        RepaymentRow repaymentRow = repaymentMapper.findById(tripId, repaymentId);
        requireConfirmableBy(repaymentRow, meMemberId);
        if (RepaymentStatus.CONFIRMED.equals(repaymentRow.status())) {
            return RepaymentView.of(repaymentRow);
        }

        repaymentMapper.confirm(tripId, repaymentId, meMemberId);

        // 有可能是并发确认或取消，回头查原因
        repaymentRow = repaymentMapper.findById(tripId, repaymentId);
        requireConfirmableBy(repaymentRow, meMemberId);

        if (RepaymentStatus.CONFIRMED.equals(repaymentRow.status())) {
            return RepaymentView.of(repaymentRow);
        }
        throw new IllegalStateException("confirm failed, status=" + repaymentRow.status() + ", repaymentId=" + repaymentId);

    }

    private static void requireConfirmableBy(RepaymentRow repaymentRow, long meMemberId) {
        if (repaymentRow == null || repaymentRow.deletedAt() != null) {
            throw ApiException.notFound();
        }
        long receiverMemberId = repaymentRow.toMemberId();
        if (receiverMemberId != meMemberId) {
            throw ApiException.forbidden("not receiver");
        }
    }

    /**
     * <ul>
     *   <li>只更新该组里 pending 且没取消的行，一条 UPDATE；组里有已取消、已确认的行都不影响；</li>
     *   <li>组不存在、或组里的行全部已取消 404；鉴权看组里的收款方（一个组只有一对 from/to），不是收款方 403；</li>
     *   <li>响应是该组<b>所有</b>行，已取消的也在其中（status = cancelled），顺序同创建：clearedCurrency 升序，再按 id。</li>
     * </ul>
     */
    List<RepaymentView> confirmGroup(long userId, long tripId, String settlementGroupId) {
        long meMemberId = memberService.requireActiveMember(userId, tripId).memberId();
        List<RepaymentRow> repaymentRows = repaymentMapper.findByGroup(tripId, settlementGroupId);
        requireConfirmableGroupBy(repaymentRows, meMemberId);
        if (countPending(repaymentRows) == 0) {
            return toViews(repaymentRows);
        }

        repaymentMapper.confirmGroup(tripId, settlementGroupId, meMemberId);

        // 有可能是并发确认或取消，回头查原因
        repaymentRows = repaymentMapper.findByGroup(tripId, settlementGroupId);
        requireConfirmableGroupBy(repaymentRows, meMemberId);
        if (countPending(repaymentRows) == 0) {
            return toViews(repaymentRows);
        }

        throw new IllegalStateException("confirmGroup failed, pendingSize=" + countPending(repaymentRows) + ", settlementGroupId=" + settlementGroupId);
    }

    /**
     * 组不存在、或组里的行全部已取消 404；没取消的行的收款方不是我 403
     */
    private static void requireConfirmableGroupBy(List<RepaymentRow> repaymentRows, long meMemberId) {
        if (repaymentRows == null || repaymentRows.isEmpty()) {
            throw ApiException.notFound();
        }
        List<RepaymentRow> repaymentRowsNotCancelled = repaymentRows.stream().filter(repaymentRow -> repaymentRow.deletedAt() == null).toList();
        if (repaymentRowsNotCancelled.isEmpty()) {
            throw ApiException.notFound();
        }
        for (RepaymentRow row : repaymentRowsNotCancelled) {
            long receiverMemberId = row.toMemberId();
            if (receiverMemberId != meMemberId) {
                throw ApiException.forbidden("not receiver");
            }
        }
    }

    /**
     * 没取消、还是 pending 的行数
     */
    private static int countPending(List<RepaymentRow> repaymentRows) {
        return (int) repaymentRows.stream()
                .filter(repaymentRow -> repaymentRow.deletedAt() == null && RepaymentStatus.PENDING.equals(repaymentRow.status()))
                .count();
    }

    /**
     * 已确认的行数
     */
    private static int countConfirmed(List<RepaymentRow> repaymentRows) {
        return (int) repaymentRows.stream()
                .filter(repaymentRow -> repaymentRow.deletedAt() == null && RepaymentStatus.CONFIRMED.equals(repaymentRow.status()))
                .count();
    }

    private static List<RepaymentView> toViews(List<RepaymentRow> repaymentRows) {
        return repaymentRows.stream().map(RepaymentView::of).collect(Collectors.toList());
    }

    /**
     * <ul>
     *   <li>付款方或收款方都可以取消；其他在籍成员 403；不是在籍成员、这条不在本行程里 404；</li>
     *   <li>已 confirmed → 409 REPAYMENT_ALREADY_CONFIRMED；已取消的重复取消 200；</li>
     *   <li>取消 = 设置 deleted_at（接口上表现为 status = cancelled），version 加一。</li>
     * </ul>
     */
    RepaymentView cancel(long userId, long tripId, long repaymentId) {
        long meMemberId = memberService.requireActiveMember(userId, tripId).memberId();
        RepaymentRow repaymentRow = repaymentMapper.findById(tripId, repaymentId);
        requireCancelableBy(repaymentRow, meMemberId);
        if (repaymentRow.deletedAt() != null) {
            return RepaymentView.of(repaymentRow);
        }

        repaymentMapper.cancel(tripId, repaymentId, meMemberId);

        // 有可能是并发确认或取消，回头查原因
        repaymentRow = repaymentMapper.findById(tripId, repaymentId);
        requireCancelableBy(repaymentRow, meMemberId);

        if (repaymentRow.deletedAt() != null) {
            return RepaymentView.of(repaymentRow);
        }

        throw new IllegalStateException("cancel failed, status=" + repaymentRow.status() + ", repaymentId=" + repaymentId);
    }

    private static void requireCancelableBy(RepaymentRow repaymentRow, long meMemberId) {
        if (repaymentRow == null) {
            throw ApiException.notFound();
        }
        long senderMemberId = repaymentRow.fromMemberId();
        long receiverMemberId = repaymentRow.toMemberId();
        if (senderMemberId != meMemberId && receiverMemberId != meMemberId) {
            throw ApiException.forbidden("not sender or receiver");
        }

        if (RepaymentStatus.CONFIRMED.equals(repaymentRow.status())) {
            throw ApiException.of(ErrorCode.REPAYMENT_ALREADY_CONFIRMED, "already confirmed");
        }
    }

    /**
     * <ul>
     *   <li>取消该组里所有 pending 的行，已确认的行保留、不报错；</li>
     *   <li>只有「一行都没取消到、且组里有已确认的行」才 409 REPAYMENT_ALREADY_CONFIRMED；
     *       组里全是已取消的，重复取消 200；组不存在 404；</li>
     *   <li>响应同确认：该组所有行，已取消的 status 为 cancelled。</li>
     * </ul>
     */
    List<RepaymentView> cancelGroup(long userId, long tripId, String settlementGroupId) {
        long meMemberId = memberService.requireActiveMember(userId, tripId).memberId();
        List<RepaymentRow> repaymentRows = repaymentMapper.findByGroup(tripId, settlementGroupId);
        requireCancelableGroupBy(repaymentRows, meMemberId);
        // 用 countPending 是因为已确认的不能被取消，那么 PENDING 的行数就是能被取消的行数
        if (countPending(repaymentRows) == 0 && countConfirmed(repaymentRows) > 0) {
            throw ApiException.of(ErrorCode.REPAYMENT_ALREADY_CONFIRMED, "already confirmed");
        } else if (countPending(repaymentRows) == 0) {
            return toViews(repaymentRows);
        }

        int cancelled = repaymentMapper.cancelGroup(tripId, settlementGroupId, meMemberId);

        // 有可能是并发确认或取消，回头查原因
        repaymentRows = repaymentMapper.findByGroup(tripId, settlementGroupId);
        requireCancelableGroupBy(repaymentRows, meMemberId);
        if (countPending(repaymentRows) == 0 && countConfirmed(repaymentRows) > 0 && cancelled < 1) {
            throw ApiException.of(ErrorCode.REPAYMENT_ALREADY_CONFIRMED, "already confirmed");
        } else if (countPending(repaymentRows) == 0) {
            return toViews(repaymentRows);
        }

        throw new IllegalStateException("cancelGroup failed, pendingSize=" + countPending(repaymentRows) + ", settlementGroupId=" + settlementGroupId);
    }

    private static void requireCancelableGroupBy(List<RepaymentRow> repaymentRows, long meMemberId) {
        if (repaymentRows == null || repaymentRows.isEmpty()) {
            throw ApiException.notFound();
        }
        repaymentRows.forEach(repaymentRow -> {
            long senderMemberId = repaymentRow.fromMemberId();
            long receiverMemberId = repaymentRow.toMemberId();
            if (senderMemberId != meMemberId && receiverMemberId != meMemberId) {
                throw ApiException.forbidden("not sender or receiver");
            }
        });
    }
}
