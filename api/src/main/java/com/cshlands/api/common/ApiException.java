package com.cshlands.api.common;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：状态码和 code 对应 docs/api-contract.md「通用约定」里的错误码表。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ErrorResponse.Field> fields;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    private ApiException(HttpStatus status, String code, String message, List<ErrorResponse.Field> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    /** 业务层发现的校验失败（比如币别不存在），格式和 @Valid 失败一致 */
    public static ApiException validationFailed(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                List.of(new ErrorResponse.Field(field, message)));
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

    /** 只有校验失败才有，其余为 null */
    public List<ErrorResponse.Field> fields() {
        return fields;
    }

}
