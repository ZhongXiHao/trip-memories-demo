package com.cshlands.api.member;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.user.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Trip 成员的业务入口；其他包只通过它操作成员，不直接碰 Mapper。
 */
@Service
public class MemberService {

    private final MemberMapper memberMapper;
    private final UserMapper userMapper;

    MemberService(MemberMapper memberMapper, UserMapper userMapper) {
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
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

    /**
     * 仅 Owner。曾经退出的用户重新加入，沿用原 memberId；否则新建成员。
     * 不先查"是不是成员"再决定，因为两个并发请求会同时查到"不是"。
     * 每一步都是单条原子 SQL，由 uk_member_trip_user 唯一键裁决谁先谁后。
     */
    AddMemberResult addMember(long userId, long tripId, MemberRequest request) {
        requireOwner(userId, tripId);
        long newUserId = request.userId();
        if (!userMapper.exists(newUserId)) {
            throw ApiException.validationFailed("userId", "user not found");
        }

        boolean created = memberMapper.reactivate(newUserId, tripId) == 0;
        if (created) {
            try {
                memberMapper.insert(new NewMember(tripId, newUserId, MemberRole.MEMBER, MemberStatus.ACTIVE));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ALREADY_MEMBER", "User is already a member of this trip");
            }
        }
        return new AddMemberResult(memberMapper.findByUser(newUserId, tripId), created);
    }

}
