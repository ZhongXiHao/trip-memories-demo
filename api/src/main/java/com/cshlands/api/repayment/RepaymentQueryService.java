package com.cshlands.api.repayment;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.common.RegexUtils;
import com.cshlands.api.member.ActiveMember;
import com.cshlands.api.member.MemberService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 还款列表。创建（RepaymentService）和确认、取消（RepaymentStatusService）各是一个用例，这里只有读。
 */
@Service
class RepaymentQueryService {
    private final MemberService memberService;
    private final RepaymentMapper repaymentMapper;

    RepaymentQueryService(MemberService memberService, RepaymentMapper repaymentMapper) {
        this.memberService = memberService;
        this.repaymentMapper = repaymentMapper;
    }

    /**
     * 规则见契约「2. 还款列表」：
     * <ul>
     *   <li>先过 {@code memberService.requireActiveMember}：不是在籍成员 404；在籍成员能看到行程里全部没取消的还款，不只是自己的；</li>
     *   <li>{@code status} 只能是 pending / confirmed，null 表示不过滤；cancelled 虽然是合法的枚举值，这里要 400，fields 指 status；</li>
     *   <li>{@code settlementGroupId} 不是 UUID 格式 400（{@code RegexUtils.isValidUuid}），fields 指 settlementGroupId；</li>
     *   <li>{@code memberId}：付款方或收款方是他；不属于本行程、已退出的都不报错，没有就是空数组；</li>
     *   <li>排序 createdAt 倒序，相同的按 id 倒序；已取消的（deleted_at 非空）不出现。</li>
     * </ul>
     * 三个查询条件都可选，用一个 RepaymentListQuery 传给 Mapper，SQL 写在 RepaymentMapper.xml 里。
     */
    List<RepaymentView> list(long userId, long tripId, RepaymentStatus status, Long memberId, String settlementGroupId) {
        memberService.requireActiveMember(userId, tripId);
        if (RepaymentStatus.CANCELLED.equals(status)) {
            throw ApiException.validationFailed("status", "cancelled is not allowed");
        }
        if (settlementGroupId != null && !RegexUtils.isValidUuid(settlementGroupId)) {
            throw ApiException.validationFailed("settlementGroupId", "invalid uuid");
        }
        List<RepaymentRow> repaymentRows = repaymentMapper.list(new RepaymentListQuery(tripId, status, memberId, settlementGroupId));
        return repaymentRows.stream().map(RepaymentView::of).toList();
    }
}
