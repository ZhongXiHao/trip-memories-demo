package com.cshlands.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 统一错误结构。调用方（包括 Agent）靠 code 决定追问、重试还是放弃，不解析 message。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<Field> fields) {

    public record Field(String field, String message) {
    }

}
