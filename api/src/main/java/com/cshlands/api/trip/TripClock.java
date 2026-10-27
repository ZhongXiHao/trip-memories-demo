package com.cshlands.api.trip;

import java.time.LocalDate;

public record TripClock(String timezone, LocalDate startDate) {
}
