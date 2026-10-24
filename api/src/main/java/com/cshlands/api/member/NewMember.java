package com.cshlands.api.member;

import lombok.Getter;
import lombok.Setter;

/** 插入 trip_member 表用；id 由 useGeneratedKeys 写回。 */
@Getter
class NewMember {

    @Setter
    private Long id;
    private final long tripId;
    private final long userId;
    private final MemberRole role;
    private final MemberStatus status;

    NewMember(long tripId, long userId, MemberRole role, MemberStatus status) {
        this.tripId = tripId;
        this.userId = userId;
        this.role = role;
        this.status = status;
    }

}
