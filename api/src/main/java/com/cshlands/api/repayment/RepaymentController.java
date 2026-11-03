package com.cshlands.api.repayment;

import com.cshlands.api.auth.CurrentUser;
import com.cshlands.api.common.PageResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trips/{tripId}/repayments")
class RepaymentController {
    private final RepaymentService repaymentService;
    private final RepaymentQueryService repaymentQueryService;
    private final RepaymentStatusService repaymentStatusService;

    RepaymentController(RepaymentService repaymentService, RepaymentQueryService repaymentQueryService,
                        RepaymentStatusService repaymentStatusService) {
        this.repaymentService = repaymentService;
        this.repaymentQueryService = repaymentQueryService;
        this.repaymentStatusService = repaymentStatusService;
    }

    /**
     * 首次创建 201；同一个 clientUuid 内容一致的重放 200，返回已有的那一组
     */
    @PostMapping
    ResponseEntity<RepaymentGroupView> create(@PathVariable long tripId, @Valid @RequestBody CreateRepaymentRequest request) {
        RepaymentResult result = repaymentService.create(CurrentUser.id(), tripId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.group());
    }

    @GetMapping
    PageResult<RepaymentView> list(
            @PathVariable long tripId,
            @RequestParam(required = false) RepaymentStatus status,
            @RequestParam(name = "memberId", required = false) Long memberId,
            @RequestParam(required = false) String settlementGroupId) {
        return new PageResult<>(repaymentQueryService.list(CurrentUser.id(), tripId, status, memberId, settlementGroupId));
    }

    @PostMapping("/{repaymentId}/confirm")
    RepaymentView confirm(@PathVariable long tripId, @PathVariable long repaymentId) {
        return repaymentStatusService.confirm(CurrentUser.id(), tripId, repaymentId);
    }

    @PostMapping("/groups/{settlementGroupId}/confirm")
    PageResult<RepaymentView> confirmGroup(@PathVariable long tripId, @PathVariable String settlementGroupId) {
        return new PageResult<>(repaymentStatusService.confirmGroup(CurrentUser.id(), tripId, settlementGroupId));
    }

    @PostMapping("/{repaymentId}/cancel")
    RepaymentView cancel(@PathVariable long tripId, @PathVariable long repaymentId) {
        return repaymentStatusService.cancel(CurrentUser.id(), tripId, repaymentId);
    }

    @PostMapping("/groups/{settlementGroupId}/cancel")
    PageResult<RepaymentView> cancelGroup(@PathVariable long tripId, @PathVariable String settlementGroupId) {
        return new PageResult<>(repaymentStatusService.cancelGroup(CurrentUser.id(), tripId, settlementGroupId));
    }
}
