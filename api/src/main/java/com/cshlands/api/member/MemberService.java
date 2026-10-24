package com.cshlands.api.member;

import com.cshlands.api.common.ApiException;
import org.springframework.stereotype.Service;

/**
 * Trip 成员的业务入口；其他包只通过它操作成员，不直接碰 Mapper。
 */
@Service
public class MemberService {

    private final MemberMapper memberMapper;

    MemberService(MemberMapper memberMapper) {
        this.memberMapper = memberMapper;
    }

    /**
     * 创建 Trip 时把创建者登记为 Owner，返回新成员的 memberId。在调用方的事务里执行。
     */
    public void createOwner(long tripId, long userId) {
        NewMember owner = new NewMember(tripId, userId, MemberRole.OWNER, MemberStatus.ACTIVE);
        memberMapper.insert(owner);
    }

    /**
     * 所有 Trip 下的接口先过这里：不是在籍成员（含已退出、Trip 不存在或已删除）一律 404，
     * 不泄露 Trip 是否存在。
     */
    public ActiveMember requireActiveMember(long userId, long tripId) {
        ActiveMember member = memberMapper.findActive(userId, tripId);
        if (member == null) {
            throw ApiException.notFound();
        }
        return member;
    }

    /**
     * 在 {@link #requireActiveMember} 的基础上要求是 Owner，否则 403
     */
    public ActiveMember requireOwner(long userId, long tripId) {
        ActiveMember member = requireActiveMember(userId, tripId);
        if (member.role() != MemberRole.OWNER) {
            throw ApiException.forbidden("Only the owner can do this");
        }
        return member;
    }

}
