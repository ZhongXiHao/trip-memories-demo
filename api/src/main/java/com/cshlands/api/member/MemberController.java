package com.cshlands.api.member;

import com.cshlands.api.auth.CurrentUser;
import com.cshlands.api.common.ApiException;
import com.cshlands.api.common.ItemsResponse;
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

    @PostMapping("/members/me/quit")
    MemberChangeResult quit(@PathVariable long tripId) {
        return memberService.quit(CurrentUser.id(), tripId);
    }

    @PostMapping("/members/{memberId}/remove")
    MemberChangeResult remove(@PathVariable long tripId, @PathVariable long memberId) {
        return memberService.remove(CurrentUser.id(), tripId, memberId);
    }

    /** ?status=active 或 left 过滤；不传则全部 */
    @GetMapping("/members")
    ItemsResponse<MemberView> list(@PathVariable long tripId, @RequestParam(required = false) MemberStatus status) {
        return new ItemsResponse<>(memberService.list(CurrentUser.id(), tripId, status));
    }

    /** {memberId} 可以写成 me，表示当前用户自己 */
    @GetMapping("/members/{memberId}")
    MemberView get(@PathVariable long tripId, @PathVariable String memberId) {
        if (memberId.equals("me")) {
            return memberService.getMe(CurrentUser.id(), tripId);
        }
        long id;
        try {
            id = Long.parseLong(memberId);
        } catch (NumberFormatException e) {
            throw ApiException.validationFailed("memberId", "must be a number or 'me'");
        }
        return memberService.get(CurrentUser.id(), tripId, id);
    }

    @PostMapping("/owner/transfer/{memberId}")
    TransferOwnerResult transferOwner(@PathVariable long tripId, @PathVariable long memberId) {
        return memberService.transferOwner(CurrentUser.id(), tripId, memberId);
    }

}
