package com.cshlands.api.member;

import com.cshlands.api.common.ValueEnum;
import com.fasterxml.jackson.annotation.JsonValue;

/** 对应 trip_member.role */
public enum MemberRole implements ValueEnum {

    OWNER("owner"),
    MEMBER("member");

    private final String value;

    MemberRole(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }

}
