package com.cshlands.api.trip;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.currency.CurrencyMapper;
import com.cshlands.api.member.MemberService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;

@Service
public class TripService {

    private final TripMapper tripMapper;
    private final MemberService memberService;
    private final CurrencyMapper currencyMapper;

    TripService(TripMapper tripMapper, MemberService memberService, CurrencyMapper currencyMapper) {
        this.tripMapper = tripMapper;
        this.memberService = memberService;
        this.currencyMapper = currencyMapper;
    }

    @Transactional
    TripView create(long userId, TripRequest request) {
        validate(request);

        NewTrip trip = new NewTrip(request, userId);
        tripMapper.insert(trip);
        memberService.createOwner(trip.getId(), userId);

        return tripMapper.findView(trip.getId(), userId);
    }

    TripView get(long userId, long tripId) {
        memberService.requireActiveMember(userId, tripId);
        return tripMapper.findView(tripId, userId);
    }

    List<TripView> list(long userId) {
        return tripMapper.listViews(userId);
    }

    TripView update(long userId, long tripId, TripRequest request) {
        memberService.requireOwner(userId, tripId);
        validate(request);

        tripMapper.update(tripId, request);
        return tripMapper.findView(tripId, userId);
    }

    public void delete(long userId, long tripId) {
        memberService.requireOwner(userId, tripId);
        tripMapper.softDelete(tripId);
    }

    private void validate(TripRequest request) {
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
    }

    public TripClock getTripClock(long userId, long tripId) {
        memberService.requireActiveMember(userId, tripId);
        TripView view = tripMapper.findView(userId, tripId);
        return new TripClock(view.timezone(), view.startDate());
    }
}
