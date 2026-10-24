package com.cshlands.api.member;

import com.cshlands.api.common.ValueEnumTypeHandler;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

@Component
@MappedTypes(MemberRole.class)
class MemberRoleTypeHandler extends ValueEnumTypeHandler<MemberRole> {

    MemberRoleTypeHandler() {
        super(MemberRole.class);
    }

}
