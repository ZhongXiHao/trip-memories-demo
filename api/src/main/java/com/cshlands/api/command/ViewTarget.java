package com.cshlands.api.command;

import com.fasterxml.jackson.annotation.JsonValue;

/** 查看类指令要看什么，前端据此调对应的接口 */
enum ViewTarget {
    /** 调欠款总览 */
    DEBTS("debts"),
    /** 调支出列表，date 为今天 */
    TODAY_EXPENSES("today_expenses");

    private final String value;

    ViewTarget(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
