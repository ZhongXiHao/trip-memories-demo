package com.cshlands.api.common;

import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.stereotype.Component;

/**
 * 查询参数、路径参数里的枚举按 {@link ValueEnum#value()} 解析（?status=active），
 * 而不是 Spring 默认的常量名（ACTIVE）。认不出的值会变成 MethodArgumentTypeMismatchException，
 * 由 GlobalExceptionHandler 转成 400 VALIDATION_FAILED。Boot 会自动注册这个 Bean。
 */
@Component
class ValueEnumConverterFactory implements ConverterFactory<String, ValueEnum> {

    @Override
    public <T extends ValueEnum> Converter<String, T> getConverter(Class<T> targetType) {
        return source -> {
            if (source.isBlank()) {
                return null;
            }
            for (T constant : targetType.getEnumConstants()) {
                if (constant.value().equals(source)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException("Unknown value '" + source + "' for " + targetType.getSimpleName());
        };
    }

}
