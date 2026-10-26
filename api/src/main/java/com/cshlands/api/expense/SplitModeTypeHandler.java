package com.cshlands.api.expense;

import com.cshlands.api.common.ValueEnumTypeHandler;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

@Component
@MappedTypes(SplitMode.class)
class SplitModeTypeHandler extends ValueEnumTypeHandler<SplitMode> {

    SplitModeTypeHandler() {
        super(SplitMode.class);
    }

}
