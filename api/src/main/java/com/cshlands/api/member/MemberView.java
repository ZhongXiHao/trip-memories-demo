package com.cshlands.api.member;

import java.time.Instant;

/** 成员对象，字段见 docs/api-contract.md「Trip 成员操作」 */
public record MemberView(
        long memberId,
        long userId,
        String nickname,
        MemberRole role,
        MemberStatus status,
        Instant joinedAt) {
}
