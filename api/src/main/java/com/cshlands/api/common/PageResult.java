package com.cshlands.api.common;

import java.util.List;

public record PageResult<T>(
        List<T> items
) {
}
