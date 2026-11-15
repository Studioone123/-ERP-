package com.erp.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Redis 会话存储：多实例共享登录态的标准做法。
 *
 * <p>与内存版最大的区别是<b>过期由 Redis 自己负责</b>：
 * 写入时带上 TTL，到点自动删除，不需要任何清理线程或惰性扫描，也不会内存泄漏。
 * 进程重启后会话仍然存在（只要没过期），用户不必重新登录。
 *
 * <p>value 用 {@code \u0001} 作分隔符拼一段短字符串，而不是 JDK 序列化对象：
 * 在 redis-cli 里能直接读懂内容，排查「这个 token 是谁」很方便。
 * 生产环境更严谨的做法是存 JSON 或 Hash，这里保持简单可读。
 */
@Component
@ConditionalOnProperty(name = "erp.session.store", havingValue = "redis")
public class RedisSessionStore implements SessionStore {

    private static final String PREFIX = "erp:session:";
    private static final String SEP = "\u0001";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisSessionStore(StringRedisTemplate redis,
                             @Value("${erp.security.token-ttl-hours:12}") long ttlHours) {
        this.redis = redis;
        this.ttl = Duration.ofHours(ttlHours);
    }

    @Override
    public String create(String username, String realName, String role) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String value = String.join(SEP, username, realName == null ? "" : realName,
                role == null ? "" : role);
        redis.opsForValue().set(PREFIX + token, value, ttl);
        return token;
    }

    @Override
    public TokenStore.User verify(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(401, "请先登录");
        }
        String value = redis.opsForValue().get(PREFIX + token);
        if (value == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        // -1 保留末尾空串，避免某人 realName 为空时数组长度变短
        String[] parts = value.split(SEP, -1);
        if (parts.length < 3) {
            throw new BizException(401, "登录状态异常，请重新登录");
        }
        return new TokenStore.User(parts[0], parts[1], parts[2]);
    }

    @Override
    public void remove(String token) {
        if (token != null) {
            redis.delete(PREFIX + token);
        }
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "redis");
        // 注意：KEYS 会阻塞 Redis，仅适合这里「运维页面偶尔看一眼」的场景；
        // 大规模生产请改成 SCAN 游标遍历。
        Set<String> keys = redis.keys(PREFIX + "*");
        m.put("activeSessions", keys == null ? 0 : keys.size());
        m.put("ttlHours", ttl.toHours());
        return m;
    }
}
