package com.cshlands.api.common;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：状态码和 code 来自 {@link ErrorCode}，对应 docs/api-contract.md「通用约定」里的错误码表。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ErrorResponse.Field> fields;

    private ApiException(ErrorCode errorCode, String message, List<ErrorResponse.Field> fields) {
        super(message);
        this.errorCode = errorCode;
        this.fields = fields;
    }

    public static ApiException of(ErrorCode errorCode, String message) {
        return new ApiException(errorCode, message, null);
    }

    /**
     * 业务层发现的校验失败（比如币别不存在），格式和 @Valid 失败一致
     */
    public static ApiException validationFailed(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                List.of(new ErrorResponse.Field(field, message)));
    }

    public static ApiException unauthenticated() {
        return of(ErrorCode.UNAUTHENTICATED, "Authentication required");
    }

    /**
     * 资源不存在、已删除，或调用者不是该 Trip 的在籍成员（不泄露 Trip 是否存在）
     */
    public static ApiException notFound() {
        return of(ErrorCode.NOT_FOUND, "Resource not found");
    }

    /**
     * 是成员但没有权限
     */
    public static ApiException forbidden(String message) {
        return of(ErrorCode.FORBIDDEN, message);
    }

    public HttpStatus status() {
        return errorCode.status();
    }

    public String code() {
        return errorCode.code();
    }

    /**
     * 只有校验失败才有，其余为 null
     */
    public List<ErrorResponse.Field> fields() {
        return fields;
    }

}
