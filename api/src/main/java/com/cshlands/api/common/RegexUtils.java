package com.cshlands.api.common;

public class RegexUtils {
    /**
     * 是否为有效的 UUID
     * @param uuid UUID 字符串
     * @return true:符合，false：不符合
     */
    public static boolean isValidUuid(String uuid) {
        return uuid != null && RegexPatterns.UUID_PATTERN.matcher(uuid).matches();
    }
}
