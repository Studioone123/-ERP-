package com.erp.common;

import java.util.Map;

/**
 * 登录会话存储策略接口（策略模式）。
 *
 * <p>为什么要把原来 {@code TokenStore} 里的 ConcurrentHashMap 抽出来？
 * 单体单实例时内存完全够用，但只要未来起第二个实例做负载均衡，
 * 用户在 A 机器登录、请求被分到 B 机器就会被判定为「未登录」。
 * 把存储抽象成接口后，切换 Redis 只改一个配置项，业务代码零改动。
 *
 * <p>两个实现由 {@code @ConditionalOnProperty} 按 {@code erp.session.store} 二选一装配：
 * <ul>
 *   <li>{@link InMemorySessionStore}：默认，零依赖，带过期清理与单用户会话上限</li>
 *   <li>{@link RedisSessionStore}：启用 redis profile 后使用，靠 Redis TTL 自动过期</li>
 * </ul>
 *
 * <p>{@link TokenStore} 保留原类名与 {@code TokenStore.User} 结构，作为对外门面，
 * 因此 AuthController / AuthInterceptor 等既有代码完全不需要修改。
 */
public interface SessionStore {

    /** 创建会话并返回令牌 */
    String create(String username, String realName, String role);

    /** 校验令牌；无效或过期时抛 401 业务异常 */
    TokenStore.User verify(String token);

    /** 注销 / 踢出会话 */
    void remove(String token);

    /** 运行时状态，供运维页面与测试观察当前用的是哪种实现 */
    Map<String, Object> stats();
}
