package com.cshlands.api.expense;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import static com.cshlands.api.common.ApiException.validationFailed;

/**
 * 支出的时间和日期：解析请求里的 occurredAt、occurredTz、date，以及算 dayIndex。
 * 都是纯函数，不查数据库，不依赖 Spring。解析失败抛 VALIDATION_FAILED，字段名指向出错的请求字段。
 */
final class ExpenseTime {

    private ExpenseTime() {
    }

    static Instant parseOccurredAt(String occurredAt) {
        try {
            return Instant.parse(occurredAt);
        } catch (DateTimeParseException e) {
            throw validationFailed("occurredAt", "must be a valid ISO-8601 timestamp with timezone, e.g. 2023-03-15T10:00:00Z or 2023-03-15T19:00:00+09:00");
        }
    }

    static String parseTz(String occurredTz) {
        if (!ZoneId.getAvailableZoneIds().contains(occurredTz)) {
            throw validationFailed("occurredTz", "must be an IANA time zone, e.g. Asia/Tokyo");
        }
        return occurredTz;
    }

    static LocalDate parseDate(String date) {
        if (date == null) {
            return null;
        }
        // 日期必须是 yyyy-MM-dd
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw validationFailed("date", "must be a valid date in the format yyyy-MM-dd");
        }
    }

    static Long calculateDayIndex(Instant occurredAt, ZoneId tz, LocalDate startDate) {
        if (startDate == null) {
            return null;
        }
        LocalDate occurredDate = occurredAt.atZone(tz).toLocalDate();
        return ChronoUnit.DAYS.between(startDate, occurredDate) + 1;
    }
}
