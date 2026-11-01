package com.cshlands.api.repayment;

import com.cshlands.api.common.ValueEnumTypeHandler;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

@Component
@MappedTypes(RepaymentStatus.class)
class RepaymentStatusTypeHandler extends ValueEnumTypeHandler<RepaymentStatus> {

    RepaymentStatusTypeHandler() {
        super(RepaymentStatus.class);
    }
}
