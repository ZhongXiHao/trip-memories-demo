package com.cshlands.api.auth;

import com.cshlands.api.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 业务代码取"当前用户"的唯一入口，不要直接读请求头。
 * 由 {@link AuthInterceptor} 认证后放进请求属性。
 */
public final class CurrentUser {

    private static final String ATTRIBUTE = CurrentUser.class.getName() + ".id";

    private CurrentUser() {
    }

    public static long id() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object id = attributes == null ? null : attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (id == null) {
            throw ApiException.unauthenticated();
        }
        return (Long) id;
    }

    static void bind(HttpServletRequest request, long userId) {
        request.setAttribute(ATTRIBUTE, userId);
    }

}
