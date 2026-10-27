package com.cshlands.api.expense;

import com.cshlands.api.common.ValueEnumTypeHandler;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

@Component
@MappedTypes(ExpenseCategory.class)
class ExpenseCategoryTypeHandler extends ValueEnumTypeHandler<ExpenseCategory> {

    ExpenseCategoryTypeHandler() {
        super(ExpenseCategory.class);
    }

}
