package com.cshlands.api.debt;

import com.cshlands.api.auth.CurrentUser;
import com.cshlands.api.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trips/{tripId}/debts")
class DebtController {
    private final DebtService debtService;

    DebtController(DebtService debtService) {
        this.debtService = debtService;
    }

    @GetMapping
    PageResult<DebtView> overview(
            @PathVariable long tripId,
            @RequestParam(name = "memberId", required = false) Long memberId
    ) {
        return new PageResult<>(debtService.overview(CurrentUser.id(), tripId, memberId));
    }
}
