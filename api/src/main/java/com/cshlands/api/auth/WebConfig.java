package com.cshlands.api.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 路径相对 context-path（/api）。auth 下是注册和登录，本来就没有登录态
        registry.addInterceptor(authInterceptor).addPathPatterns("/**").excludePathPatterns("/auth/**", "/error");
    }

}
