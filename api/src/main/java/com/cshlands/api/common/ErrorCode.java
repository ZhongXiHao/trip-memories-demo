package com.cshlands.api.common;

import org.springframework.http.HttpStatus;

/**
 * 与 docs/api-contract.md「通用约定」里的错误码表一一对应：每个错误码对应的状态码只在这里定义一次。
 * 调用方靠 {@link #code()} 决定追问、重试还是放弃，所以常量名就是对外的错误码，不能随意改名。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    AMOUNT_NOT_ROUNDABLE(HttpStatus.BAD_REQUEST),
    SPLIT_SUM_MISMATCH(HttpStatus.BAD_REQUEST),
    MEMBER_NOT_IN_TRIP(HttpStatus.BAD_REQUEST),
    MEMBER_LEFT(HttpStatus.BAD_REQUEST),
    REPAYMENT_EXCEEDS_DEBT(HttpStatus.BAD_REQUEST),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),

    FORBIDDEN(HttpStatus.FORBIDDEN),

    NOT_FOUND(HttpStatus.NOT_FOUND),

    VERSION_CONFLICT(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    ALREADY_MEMBER(HttpStatus.CONFLICT),
    OWNER_MUST_TRANSFER_FIRST(HttpStatus.CONFLICT),
    CANNOT_REMOVE_OWNER(HttpStatus.CONFLICT),
    REPAYMENT_ALREADY_CONFIRMED(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** 响应体里的 code 字段 */
    public String code() {
        return name();
    }

}
