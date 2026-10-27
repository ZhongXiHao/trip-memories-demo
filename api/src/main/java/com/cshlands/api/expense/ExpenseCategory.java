package com.cshlands.api.expense;

import com.cshlands.api.common.ValueEnum;
import com.fasterxml.jackson.annotation.JsonValue;

enum ExpenseCategory implements ValueEnum {
    //flight lodging food transport shopping ticket other，默认 other
    FLIGHT("flight"),
    LODGING("lodging"),
    FOOD("food"),
    TRANSPORT("transport"),
    SHOPPING("shopping"),
    TICKET("ticket"),
    OTHER("other");
    private final String value;

    ExpenseCategory(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }
}
