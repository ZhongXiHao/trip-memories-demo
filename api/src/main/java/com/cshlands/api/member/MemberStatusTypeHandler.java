package com.cshlands.api.member;

import com.cshlands.api.common.ValueEnumTypeHandler;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

@Component
@MappedTypes(MemberStatus.class)
class MemberStatusTypeHandler extends ValueEnumTypeHandler<MemberStatus> {

    MemberStatusTypeHandler() {
        super(MemberStatus.class);
    }

}
