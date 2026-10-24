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

    /**
     * 业务层发现的校验失败（比如币别不存在），格式和 @Valid 失败一致
     */
    public static ApiException validationFailed(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                List.of(new ErrorResponse.Field(field, message)));
    }

    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
    }

    /**
     * 资源不存在、已删除，或调用者不是该 Trip 的在籍成员（不泄露 Trip 是否存在）
     */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }

    /**
     * 是成员但没有权限
     */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /**
     * 只有校验失败才有，其余为 null
     */
    public List<ErrorResponse.Field> fields() {
        return fields;
    }

}
