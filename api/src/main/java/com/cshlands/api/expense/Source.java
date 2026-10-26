package com.cshlands.api.expense;

import com.cshlands.api.common.ValueEnum;
import com.fasterxml.jackson.annotation.JsonValue;

enum Source implements ValueEnum {
    // form command agent，默认 form
    FORM("form"),
    COMMAND("command"),
    AGENT("agent");

    private final String value;

    Source(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }

}
