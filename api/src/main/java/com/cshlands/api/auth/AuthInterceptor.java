package com.cshlands.api.auth;

import com.cshlands.api.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.OptionalLong;

/**
 * 认证入口：认出当前用户并放进请求，认不出一律 401。
 * 没有任何 {@link UserResolver} 时（非 dev 且还没做真登录）所有受保护接口都是 401，不会退化成匿名或默认用户。
 */
@Component
class AuthInterceptor implements HandlerInterceptor {

    private final ObjectProvider<UserResolver> userResolver;

    AuthInterceptor(ObjectProvider<UserResolver> userResolver) {
        this.userResolver = userResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserResolver resolver = userResolver.getIfAvailable();
        OptionalLong userId = resolver == null ? OptionalLong.empty() : resolver.resolve(request);
        if (userId.isEmpty()) {
            throw ApiException.unauthenticated();
        }
        CurrentUser.bind(request, userId.getAsLong());
        return true;
    }

}
