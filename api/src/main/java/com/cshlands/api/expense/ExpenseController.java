package com.cshlands.api.expense;

import com.cshlands.api.auth.CurrentUser;
import com.cshlands.api.common.PageResult;
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

    @GetMapping("/{expenseId}")
    ExpenseView get(@PathVariable long tripId, @PathVariable long expenseId) {
        return expenseService.get(CurrentUser.id(), tripId, expenseId);
    }

    /**
     * 软删除用 POST，见契约
     */
    @PostMapping("/{expenseId}/delete")
    ResponseEntity<Void> delete(@PathVariable long tripId, @PathVariable long expenseId) {
        expenseService.delete(CurrentUser.id(), tripId, expenseId);
        return ResponseEntity.noContent().build();
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

    @GetMapping
    PageResult<ExpenseView> list(
            @PathVariable long tripId,
            @RequestParam(required = false) String  date,
            @RequestParam(name = "memberId", required = false) Long queryMemberId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) ExpenseCategory category
    ) {
        return expenseService.list(CurrentUser.id(), tripId, date, queryMemberId, currency, category);
    }
}
