package com.erp.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认会话存储：进程内存。
 *
 * <p><b>修掉的问题</b>：原 TokenStore 只有在「恰好有人拿某个过期 token 来请求」时
 * 才会 remove 它。用户反复登录却从不退出时，过期会话会一直留在 Map 里，
 * 长时间运行就是一条缓慢的内存泄漏。这里补了两件事：
 * <ol>
 *   <li>每次创建新会话时顺带做一次全量过期清理（惰性清理，均摊成本极低）</li>
 *   <li>同一用户最多保留 {@value #MAX_PER_USER} 个会话，超出时淘汰最早过期的那个，
 *       避免脚本反复调用登录接口把内存撑满</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "erp.session.store", havingValue = "memory", matchIfMissing = true)
public class InMemorySessionStore implements SessionStore {

    private static final int MAX_PER_USER = 10;

    private record Session(TokenStore.User user, String username, long expireAt) {
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public InMemorySessionStore(@Value("${erp.security.token-ttl-hours:12}") long ttlHours) {
        this.ttlMillis = ttlHours * 3600_000L;
    }

    @Override
    public String create(String username, String realName, String role) {
        long now = System.currentTimeMillis();
        purgeExpired(now);

        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(new TokenStore.User(username, realName, role),
                username, now + ttlMillis));

        // 只保留该用户最新的若干会话：按 expireAt 升序，删掉最早的那批
        List<Map.Entry<String, Session>> mine = sessions.entrySet().stream()
                .filter(e -> Objects.equals(e.getValue().username(), username))
                .sorted(Comparator.comparingLong(e -> e.getValue().expireAt()))
                .toList();
        for (int i = 0; i < mine.size() - MAX_PER_USER; i++) {
            sessions.remove(mine.get(i).getKey());
        }
        return token;
    }

    @Override
    public TokenStore.User verify(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(401, "请先登录");
        }
        Session s = sessions.get(token);
        if (s == null || s.expireAt() < System.currentTimeMillis()) {
            sessions.remove(token);
            throw new BizException(401, "登录已过期，请重新登录");
        }
        return s.user();
    }

    @Override
    public void remove(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    @Override
    public Map<String, Object> stats() {
        long now = System.currentTimeMillis();
        long active = sessions.values().stream().filter(s -> s.expireAt() >= now).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "memory");
        m.put("activeSessions", active);
        m.put("totalEntries", sessions.size());
        m.put("ttlHours", ttlMillis / 3600_000L);
        return m;
    }

    private void purgeExpired(long now) {
        // ConcurrentHashMap 的 entrySet().removeIf 是线程安全的，不需要额外加锁
        sessions.entrySet().removeIf(e -> e.getValue().expireAt() < now);
    }
}
