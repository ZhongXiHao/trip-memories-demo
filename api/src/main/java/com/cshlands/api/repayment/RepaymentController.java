package com.cshlands.api.repayment;

import com.cshlands.api.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trips/{tripId}/repayments")
class RepaymentController {
    private final RepaymentService repaymentService;

    RepaymentController(RepaymentService repaymentService) {
        this.repaymentService = repaymentService;
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
}
