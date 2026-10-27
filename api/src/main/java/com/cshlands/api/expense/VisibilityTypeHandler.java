package com.cshlands.api.expense;

import com.cshlands.api.common.ValueEnumTypeHandler;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

@Component
@MappedTypes(Visibility.class)
class VisibilityTypeHandler extends ValueEnumTypeHandler<Visibility> {
    public VisibilityTypeHandler() {
        super(Visibility.class);
    }
}
