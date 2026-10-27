package com.cshlands.api.common;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage(), e.fields()));
    }

    /** 请求体上的 @Valid 校验失败：逐字段列出哪里不对 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException e) {
        List<ErrorResponse.Field> fields = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.Field(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationFailed("Request validation failed", fields);
    }

    /** 请求体缺失或不是合法 JSON、字段类型对不上 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        return validationFailed("Request body is missing or malformed", null);
    }

    /** 路径或查询参数类型不对，比如 /trips/abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return validationFailed("Request validation failed",
                List.of(new ErrorResponse.Field(e.getName(), "invalid value")));
    }

    /** 框架层发现的请求问题，统一成 VALIDATION_FAILED；fields 为 null 时响应里不出现这个字段 */
    private static ResponseEntity<ErrorResponse> validationFailed(String message, List<ErrorResponse.Field> fields) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status()).body(new ErrorResponse(code.code(), message, fields));
    }

}
