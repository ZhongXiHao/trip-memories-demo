package com.cshlands.api.trip;

import com.cshlands.api.auth.CurrentUser;
import com.cshlands.api.common.ItemsResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trips")
class TripController {

    private final TripService tripService;

    TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @PostMapping
    ResponseEntity<TripView> create(@Valid @RequestBody TripRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tripService.create(CurrentUser.id(), request));
    }

    @GetMapping
    ItemsResponse<TripView> list() {
        return new ItemsResponse<>(tripService.list(CurrentUser.id()));
    }

    @GetMapping("/{tripId}")
    TripView get(@PathVariable long tripId) {
        return tripService.get(CurrentUser.id(), tripId);
    }

    @PutMapping("/{tripId}")
    TripView update(@PathVariable long tripId, @Valid @RequestBody TripRequest request) {
        return tripService.update(CurrentUser.id(), tripId, request);
    }

    /** 软删除用 POST，见契约 */
    @PostMapping("/{tripId}/delete")
    ResponseEntity<Void> delete(@PathVariable long tripId) {
        tripService.delete(CurrentUser.id(), tripId);
        return ResponseEntity.noContent().build();
    }

}
