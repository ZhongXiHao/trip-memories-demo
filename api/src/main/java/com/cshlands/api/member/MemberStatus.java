package com.cshlands.api.member;

import com.cshlands.api.common.ValueEnum;
import com.fasterxml.jackson.annotation.JsonValue;

/** 对应 trip_member.status；退出不删行，只改成 LEFT */
enum MemberStatus implements ValueEnum {

    ACTIVE("active"),
    LEFT("left");

    private final String value;

    MemberStatus(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }

}
