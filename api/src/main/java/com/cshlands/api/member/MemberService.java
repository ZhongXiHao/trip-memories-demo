package com.cshlands.api.member;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.common.ErrorCode;
import com.cshlands.api.user.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
     * 请求里引用的 memberId（付款人、分摊者等）都必须是这个 Trip 的在籍成员。按传入顺序检查，抛出遇到的第一个问题：
     * 不属于这个 Trip → 400 MEMBER_NOT_IN_TRIP，已退出 → 400 MEMBER_LEFT。
     * 不检查调用者自己，那一步先过 {@link #requireActiveMember}。
     */
    public void requireActiveMembers(long tripId, Collection<Long> memberIds) {
        Map<Long, MemberStatus> statusById = memberMapper.list(tripId, null).stream()
                .collect(Collectors.toMap(MemberView::memberId, MemberView::status));
        for (long memberId : memberIds) {
            MemberStatus status = statusById.get(memberId);
            if (status == null) {
                throw ApiException.of(ErrorCode.MEMBER_NOT_IN_TRIP,
                        "Member " + memberId + " is not in trip " + tripId);
            }
            if (status != MemberStatus.ACTIVE) {
                throw memberLeft();
            }
        }
    }

    /**
     * 在 {@link #requireActiveMember} 的基础上要求是 Owner，否则 403
     */
    public ActiveMember requireOwner(long userId, long tripId) {
        ActiveMember member = requireActiveMember(userId, tripId);
        if (member.role() != MemberRole.OWNER) {
            throw ownerOnly();
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
                throw ApiException.of(ErrorCode.ALREADY_MEMBER, "User is already a member of this trip");
            }
        }
        return new AddMemberResult(memberMapper.findByUser(newUserId, tripId), created);
    }

    /** 成员自己退出。参数里的是 userId（当前登录的人）。 */
    MemberChangeResult quit(long userId, long tripId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (memberMapper.markLeft(userId, tripId) > 0) {
                return new MemberChangeResult(memberMapper.findByUser(userId, tripId), List.of());
            }
            ActiveMember member = requireActiveMember(userId, tripId);   // 不在籍 → 404
            if (member.role() == MemberRole.OWNER) {
                throw ApiException.of(ErrorCode.OWNER_MUST_TRANSFER_FIRST, "Owner cannot quit the trip, please transfer ownership first");
            }
            // 走到这里：UPDATE 时他还是 Owner，之后刚好被转让、已降为普通成员。再退一次必然成功。
        }
        throw new IllegalStateException("Quit failed repeatedly for user " + userId + " in trip " + tripId);
    }

    /**
     * 仅 Owner。注意 memberId 是 trip_member.id，不是 userId，两者不是同一个编号。
     * 对已退出的成员重复调用返回 200（幂等）；memberId 不属于这个 Trip → 404；目标是 Owner → 409。
     */
    MemberChangeResult remove(long userId, long tripId, long memberId) {
        ActiveMember me = requireOwner(userId, tripId); // 检查自己是 Owner，否则 403；不在籍 → 404
        if (me.memberId() == memberId) {
            throw cannotRemoveOwner();
        }
        if (memberMapper.findById(memberId, tripId) == null) { // memberId 不属于这个 Trip → 404
            throw ApiException.notFound();
        }

        memberMapper.markLeftById(memberId, tripId);
        MemberView after = memberMapper.findById(memberId, tripId);
        if (after.status() == MemberStatus.ACTIVE) {
            // 仍在籍：他在上面两步之间刚好被转让成了 Owner，UPDATE 因此没有生效
            throw cannotRemoveOwner();
        }
        return new MemberChangeResult(after, List.of());
    }

    /**
     * 仅当前 Owner。先把旧 Owner 降为 member，再升新 Owner，两步在同一个事务里：
     * 第二步失败时第一步回滚，否则这个 Trip 会没有 Owner。
     * 并发的第二次转让会在降级这一步影响 0 行（他已经不是 Owner 了），返回 403。
     */
    @Transactional
    TransferOwnerResult transferOwner(long userId, long tripId, long memberId) {
        ActiveMember me = requireOwner(userId, tripId);
        if (me.memberId() == memberId) {
            throw ApiException.validationFailed("memberId", "cannot transfer ownership to yourself");
        }
        MemberView target = memberMapper.findById(memberId, tripId);
        if (target == null) {
            throw ApiException.notFound();
        }
        if (target.status() != MemberStatus.ACTIVE) {
            throw memberLeft();
        }

        if (memberMapper.demoteToMember(me.memberId(), tripId) == 0) {
            throw ownerOnly();
        }
        if (memberMapper.promoteToOwner(memberId, tripId) == 0) {
            throw memberLeft();
        }
        return new TransferOwnerResult(
                memberMapper.findById(me.memberId(), tripId),
                memberMapper.findById(memberId, tripId));
    }

    /** 在籍成员可看；包含已退出的成员，status 为 null 时不过滤 */
    List<MemberView> list(long userId, long tripId, MemberStatus status) {
        requireActiveMember(userId, tripId);
        return memberMapper.list(tripId, status);
    }

    /** 在籍成员可看这个 Trip 里的任何成员（含已退出的）；memberId 不属于这个 Trip → 404 */
    MemberView get(long userId, long tripId, long memberId) {
        requireActiveMember(userId, tripId);
        MemberView member = memberMapper.findById(memberId, tripId);
        if (member == null) {
            throw ApiException.notFound();
        }
        return member;
    }

    /** 路径里写 me 时：当前用户在这个 Trip 里的成员 */
    MemberView getMe(long userId, long tripId) {
        return get(userId, tripId, requireActiveMember(userId, tripId).memberId());
    }

    private static ApiException memberLeft() {
        return ApiException.of(ErrorCode.MEMBER_LEFT, "The target member has left the trip");
    }

    private static ApiException cannotRemoveOwner() {
        return ApiException.of(ErrorCode.CANNOT_REMOVE_OWNER, "Owner cannot be removed, please transfer ownership first");
    }

    private static ApiException ownerOnly() {
        return ApiException.forbidden("Only the owner can do this");
    }

}
