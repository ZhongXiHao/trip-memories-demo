package com.cshlands.api.expense;

import com.cshlands.api.common.ValueEnumTypeHandler;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

@Component
@MappedTypes(Source.class)
class SourceTypeHandler extends ValueEnumTypeHandler<Source> {

    SourceTypeHandler() {
        super(Source.class);
    }
}
