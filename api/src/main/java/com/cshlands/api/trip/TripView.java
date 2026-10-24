package com.cshlands.api.trip;

import com.cshlands.api.member.MemberRole;

import java.time.Instant;
import java.time.LocalDate;

/** Trip 对象，字段见 docs/api-contract.md「Trip」。myMemberId、myRole 是当前用户在这个 Trip 里的成员信息。 */
record TripView(
        long id,
        String name,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        String timezone,
        String displayCurrency,
        long myMemberId,
        MemberRole myRole,
        Instant createdAt) {
}
