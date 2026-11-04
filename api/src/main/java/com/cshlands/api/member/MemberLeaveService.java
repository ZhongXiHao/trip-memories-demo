package com.cshlands.api.member;

import com.cshlands.api.common.Warning;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
class MemberLeaveService {
    private final MemberService memberService;
    private final LeaveWarnings leaveWarnings;

    MemberLeaveService(MemberService memberService, LeaveWarnings leaveWarnings) {
        this.memberService = memberService;
        this.leaveWarnings = leaveWarnings;
    }

    /** 成员自己退出。参数里的是 userId（当前登录的人）。 */
    MemberChangeResult quit(long userId, long tripId) {
        MemberView quit = memberService.quit(userId, tripId);
        List<Warning> warnings = leaveWarnings.of(tripId, quit.memberId());
        return new MemberChangeResult(quit, warnings);
    }

    /**
     * 仅 Owner。注意 memberId 是 trip_member.id，不是 userId，两者不是同一个编号。
     * 对已退出的成员重复调用返回 200（幂等）；memberId 不属于这个 Trip → 404；目标是 Owner → 409。
     */
    MemberChangeResult remove(long userId, long tripId, long memberId) {
        MemberView removed = memberService.remove(userId, tripId, memberId);
        List<Warning> warnings = leaveWarnings.of(tripId, removed.memberId());
        return new MemberChangeResult(removed, warnings);
    }

}
