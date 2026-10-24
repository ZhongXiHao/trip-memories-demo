package com.cshlands.api.trip;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** 插入 trip 表用；id 由 useGeneratedKeys 写回。请求体用 {@link TripRequest}，不要混用。 */
@Getter
class NewTrip {

    @Setter
    private Long id;
    private final String name;
    private final String description;
    private final String displayCurrency;
    private final String timezone;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final long createdBy;

    NewTrip(TripRequest request, long createdBy) {
        this.name = request.name();
        this.description = request.description();
        this.displayCurrency = request.displayCurrency();
        this.timezone = request.timezone();
        this.startDate = request.startDate();
        this.endDate = request.endDate();
        this.createdBy = createdBy;
    }

}
