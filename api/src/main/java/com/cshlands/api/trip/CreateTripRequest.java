package com.cshlands.api.trip;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * POST /trips 的请求体。只放客户端能填的字段；币别是否存在、时区是否合法、日期先后
 * 要查库或跨字段判断，放在 service 里。
 */
record CreateTripRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description,
        @NotBlank String displayCurrency,
        @NotBlank String timezone,
        LocalDate startDate,
        LocalDate endDate) {
}
