package com.cshlands.api.user;

import com.cshlands.api.auth.CurrentUser;
import com.cshlands.api.common.ApiException;
import com.cshlands.api.common.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
class UserController {

    private final UserMapper userMapper;

    UserController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @GetMapping("/me")
    UserView me() {
        UserView user = userMapper.findById(CurrentUser.id());
        if (user == null) {
            throw ApiException.of(ErrorCode.NOT_FOUND, "User not found");
        }
        return user;
    }

}
