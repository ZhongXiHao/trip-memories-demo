package com.cshlands.api.command;

import com.fasterxml.jackson.annotation.JsonValue;

enum CommandKind {
    /** 产出支出草稿 */
    EXPENSE("expense"),
    /** 查看类，不产出草稿 */
    VIEW("view");

    private final String value;

    CommandKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
