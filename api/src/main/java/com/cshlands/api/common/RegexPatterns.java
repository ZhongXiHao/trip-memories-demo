package com.cshlands.api.common;

import java.util.regex.Pattern;

class RegexPatterns {
    /** 编译一次，反复使用 */
    static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
}
