package com.cshlands.api.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：状态码和 code 对应 docs/api-contract.md「通用约定」里的错误码表。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

}
