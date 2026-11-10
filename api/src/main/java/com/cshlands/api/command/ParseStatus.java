package com.cshlands.api.command;

import com.fasterxml.jackson.annotation.JsonValue;

enum ParseStatus {
    /** 可以直接预览确认 */
    OK("ok"),
    /** 有无法识别的部分 */
    FAILED("failed"),
    /** 指令认得但还没开放 */
    UNSUPPORTED("unsupported");

    private final String value;

    ParseStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
