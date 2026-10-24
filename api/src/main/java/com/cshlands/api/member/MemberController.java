package com.cshlands.api.member;

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
@RequestMapping("/trips/{tripId}")
class MemberController {

    private final MemberService memberService;

    MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * 新建成员 201，曾经退出的成员重新加入 200
     */
    @PostMapping("/members")
    ResponseEntity<MemberView> add(@PathVariable long tripId, @Valid @RequestBody MemberRequest request) {
        AddMemberResult result = memberService.addMember(CurrentUser.id(), tripId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.member());
    }

}
