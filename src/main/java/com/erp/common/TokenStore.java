package com.erp.common;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 登录令牌门面（Facade）。
 *
 * <p>对外 API 与重构前保持一致 —— 类名、{@code User} record、create/verify/remove
 * 三个方法的签名都没变，所以 AuthController、AuthInterceptor 等调用方无需感知底层变化。
 * 真正的存储交给 {@link SessionStore} 策略实现，由配置决定用内存还是 Redis。
 * 这是「开闭原则」的一个小例子：扩展新存储（比如数据库、JWT）只需新增实现类，不动门面。
 */
@Component
public class TokenStore {

    public record User(String username, String realName, String role) {
    }

    private final SessionStore delegate;

    public TokenStore(SessionStore delegate) {
        this.delegate = delegate;
    }

    public String create(User user) {
        return delegate.create(user.username(), user.realName(), user.role());
    }

    public User verify(String token) {
        return delegate.verify(token);
    }

    public void remove(String token) {
        delegate.remove(token);
    }

    /** 供运维端点查看当前会话存储类型与活跃会话数 */
    public Map<String, Object> stats() {
        return delegate.stats();
    }
}
