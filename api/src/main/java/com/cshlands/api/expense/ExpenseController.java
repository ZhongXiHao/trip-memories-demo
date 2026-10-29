package com.cshlands.api.expense;

import com.cshlands.api.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /**
     * 首次创建 201；同一个 clientUuid 内容一致的重放 200，返回已有记录
     */
    @PostMapping
    ResponseEntity<ExpenseView> create(@PathVariable long tripId, @Valid @RequestBody ExpenseRequest request) {
        ExpenseResult result = expenseService.create(CurrentUser.id(), tripId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.expenseView());
    }
}
