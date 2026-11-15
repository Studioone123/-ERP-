package com.erp.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 统一安全闸门：所有 /api/** 接口（登录除外）都要过两关：
 * <ol>
 *   <li><b>认证</b>：X-Token 是否有效（TokenStore）</li>
 *   <li><b>授权</b>：当前角色能否访问该模块（RolePermission）</li>
 * </ol>
 * 认证与授权分开，是金蝶等 ERP 权限体系的基本分层：
 * 「你是谁」与「你能做什么」是两件不同的事。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenStore tokenStore;

    public AuthInterceptor(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = request.getHeader("X-Token");
        TokenStore.User user = tokenStore.verify(token);

        // 授权校验放在设置 ThreadLocal 之前：拒绝时不会留下脏的线程变量
        if (!RolePermission.canAccess(user.role(), request.getRequestURI(), request.getMethod())) {
            throw new BizException(403, "当前角色无权访问该模块，请联系管理员");
        }
        UserContext.set(user.username());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
