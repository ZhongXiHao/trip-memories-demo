package com.cshlands.api.command;

import com.cshlands.api.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trips/{tripId}/commands")
class CommandController {
    private final CommandService commandService;

    CommandController(CommandService commandService) {
        this.commandService = commandService;
    }

    /** 解析失败也是 200，见契约「规则指令」 */
    @PostMapping("/parse")
    CommandParseView parse(@PathVariable long tripId, @Valid @RequestBody CommandParseRequest request) {
        return commandService.parse(CurrentUser.id(), tripId, request.input());
    }
}
