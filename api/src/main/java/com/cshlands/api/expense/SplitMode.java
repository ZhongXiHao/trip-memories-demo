package com.cshlands.api.expense;

import com.cshlands.api.common.ValueEnum;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SplitMode implements ValueEnum {
    EQUAL("equal"),
    CUSTOM("custom"),
    NONE("none");

    private final String value;

    SplitMode(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }
}
