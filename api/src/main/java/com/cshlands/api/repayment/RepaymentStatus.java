package com.cshlands.api.repayment;

import com.cshlands.api.common.ValueEnum;
import com.fasterxml.jackson.annotation.JsonValue;

enum RepaymentStatus implements ValueEnum {
    PENDING("pending"),
    CONFIRMED("confirmed"),
    CANCELLED("cancelled");

    private final String value;

    RepaymentStatus(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }
}
