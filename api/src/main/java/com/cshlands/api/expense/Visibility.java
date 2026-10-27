package com.cshlands.api.expense;

import com.cshlands.api.common.ValueEnum;

enum Visibility implements ValueEnum {
    TRIP("trip"),
    PRIVATE("private");

    private final String value;

    Visibility(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
