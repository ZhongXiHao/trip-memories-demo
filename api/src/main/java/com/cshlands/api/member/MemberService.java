package com.cshlands.api.member;

import org.springframework.stereotype.Service;

/** Trip 成员的业务入口；其他包只通过它操作成员，不直接碰 Mapper。 */
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

}
