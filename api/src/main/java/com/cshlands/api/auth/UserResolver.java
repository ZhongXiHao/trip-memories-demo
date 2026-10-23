package com.cshlands.api.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.OptionalLong;

/**
 * 从请求里认出当前用户。认不出就返回 empty，由拦截器统一回 401。
 * 开发期实现是 {@link DevHeaderUserResolver}；接入 Agent 前换成「验证码登录 + Redis token」的实现，其他代码不用动。
 */
public interface UserResolver {

    OptionalLong resolve(HttpServletRequest request);

}
