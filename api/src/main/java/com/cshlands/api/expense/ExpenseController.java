package com.cshlands.api.expense;

import com.cshlands.api.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/trips/{tripId}/expenses")
class ExpenseController {
    private final ExpenseService expenseService;

    ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    @PostMapping("/preview")
    ExpensePreview preview(@PathVariable long tripId, @Valid @RequestBody ExpenseRequest request) {
        return expenseService.preview(CurrentUser.id(), tripId, request);
    }
}
