package com.cshlands.api.common;

/**
 * 库里的 ENUM 列和 JSON 里都用小写字符串（owner、active），Java 常量用大写。
 * 实现这个接口的枚举由 {@link ValueEnumTypeHandler} 的子类负责和库互转。
 */
public interface ValueEnum {

    String value();

}
