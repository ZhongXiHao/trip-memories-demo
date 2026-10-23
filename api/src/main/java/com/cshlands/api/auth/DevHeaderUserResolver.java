package com.cshlands.api.auth;

import com.cshlands.api.user.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;

/**
 * 开发期身份：信任请求头 X-User-Id。
 * 只在 dev profile 下注册成 bean；其他环境根本没有这个类，不是"注册了但忽略"，
 * 所以改请求头冒充用户在非 dev 环境走不通（见 CurrentUserProdTest）。
 */
@Component
@Profile("dev")
class DevHeaderUserResolver implements UserResolver {

    static final String HEADER = "X-User-Id";

    private final UserMapper userMapper;

    DevHeaderUserResolver(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public OptionalLong resolve(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null) {
            return OptionalLong.empty();
        }
        long id;
        try {
            id = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
        return userMapper.findById(id) != null ? OptionalLong.of(id) : OptionalLong.empty();
    }

}
