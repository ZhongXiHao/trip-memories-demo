package com.cshlands.api.common;

import java.util.List;

public record ItemsResponse<T>(List<T> items) {
}
