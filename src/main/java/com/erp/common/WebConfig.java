package com.erp.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final CacheEvictInterceptor cacheEvictInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, CacheEvictInterceptor cacheEvictInterceptor) {
        this.authInterceptor = authInterceptor;
        this.cacheEvictInterceptor = cacheEvictInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 先过安全闸门，再注册缓存失效。两者都用 afterCompletion 清理自己的状态：
        // 前者清 ThreadLocal 里的当前用户，后者清业务缓存。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/public/**");
        registry.addInterceptor(cacheEvictInterceptor)
                .addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // 允许自定义头：X-Token（认证）与 X-Trace-Id（链路追踪）
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id");
    }
}
