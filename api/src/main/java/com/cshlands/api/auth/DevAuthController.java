package com.cshlands.api.auth;

import com.cshlands.api.user.NewUser;
import com.cshlands.api.user.UserMapper;
import com.cshlands.api.user.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Profile("dev")
class DevAuthController {
    private final UserMapper userMapper;

    DevAuthController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @PostMapping("/register")
    ResponseEntity<UserView> register(@Valid @RequestBody RegisterRequest request) {
        NewUser newUser = new NewUser(request.nickname());
        userMapper.insert(newUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(new UserView(newUser.getId(), newUser.getNickname()));
    }
}

record RegisterRequest(@NotBlank @Size(max = 64) String nickname) {
}