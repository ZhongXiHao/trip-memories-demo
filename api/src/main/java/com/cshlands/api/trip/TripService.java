package com.cshlands.api.trip;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.currency.CurrencyMapper;
import com.cshlands.api.member.MemberService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;

@Service
class TripService {

    private final TripMapper tripMapper;
    private final MemberService memberService;
    private final CurrencyMapper currencyMapper;

    TripService(TripMapper tripMapper, MemberService memberService, CurrencyMapper currencyMapper) {
        this.tripMapper = tripMapper;
        this.memberService = memberService;
        this.currencyMapper = currencyMapper;
    }

    @Transactional
    public TripView create(long userId, CreateTripRequest request) {
        if (request.startDate() != null && request.endDate() != null
                && request.endDate().isBefore(request.startDate())) {
            throw ApiException.validationFailed("endDate", "must not be before startDate");
        }
        if (!currencyMapper.exists(request.displayCurrency())) {
            throw ApiException.validationFailed("displayCurrency", "unsupported currency");
        }
        if (!ZoneId.getAvailableZoneIds().contains(request.timezone())) {
            throw ApiException.validationFailed("timezone", "must be an IANA time zone, e.g. Asia/Tokyo");
        }

        NewTrip trip = new NewTrip(request, userId);
        tripMapper.insert(trip);
        memberService.createOwner(trip.getId(), userId);

        return tripMapper.findView(trip.getId(), userId);
    }

}
