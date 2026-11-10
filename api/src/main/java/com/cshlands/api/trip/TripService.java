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
        if (!currencyMapper.exists(request.defaultCurrency())) {
            throw ApiException.validationFailed("defaultCurrency", "unsupported currency");
        }
        if (!ZoneId.getAvailableZoneIds().contains(request.timezone())) {
            throw ApiException.validationFailed("timezone", "must be an IANA time zone, e.g. Asia/Tokyo");
        }
    }

    /** 行程的默认币别：规则指令没写币别时取它 */
    public String getDefaultCurrency(long userId, long tripId) {
        memberService.requireActiveMember(userId, tripId);
        return tripMapper.findView(tripId, userId).defaultCurrency();
    }

    public TripClock getTripClock(long userId, long tripId) {
        memberService.requireActiveMember(userId, tripId);
        TripView view = tripMapper.findView(tripId, userId);
        return new TripClock(view.timezone(), view.startDate());
    }
}
