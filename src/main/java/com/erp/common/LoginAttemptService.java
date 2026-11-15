package com.erp.common;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败锁定（防暴力破解）。
 *
 * <p>规则：同一账号连续失败 5 次，锁定 5 分钟；期间即使密码正确也拒绝登录。
 * 成功后清零计数。计数带滑动过期：距离上次失败超过锁定时长后自动重新开始。
 *
 * <p><b>边界与取舍（面试常问）</b>：
 * <ul>
 *   <li>这里是单机内存计数（ConcurrentHashMap + 原子计数），重启即清空、多实例不共享。
 *       集群部署应替换成 Redis 的 {@code INCR key + EXPIRE key 300}，
 *       把「计数」和「过期」交给 Redis 原子完成；接口不变，换实现类即可。</li>
 *   <li>按账号维度锁定，能挡住撞库；但要防「故意输错把别人账号锁住」的骚扰，
 *       生产上还会叠加 IP 维度限流与图形验证码。</li>
 *   <li>过期记录用惰性清理（读写时顺手删），不额外起定时线程，避免后台任务拖累应用关闭。</li>
 * </ul>
 */
@Service
public class LoginAttemptService {

    /** 允许的最大连续失败次数 */
    private static final int MAX_ATTEMPTS = 5;
    /** 锁定时长（毫秒） */
    private static final long LOCK_MILLIS = 5 * 60 * 1000L;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 登录前置检查：已被锁定则直接抛业务异常，连查库都不做 */
    public void assertNotLocked(String username) {
        Attempt a = attempts.get(key(username));
        if (a == null) {
            return;
        }
        long left = a.firstFailedAt + LOCK_MILLIS - System.currentTimeMillis();
        if (a.count.get() >= MAX_ATTEMPTS && left > 0) {
            throw new BizException(429, "连续输错 " + MAX_ATTEMPTS + " 次，账号已锁定，请在 "
                    + Math.max(1, (left + 999) / 1000) + " 秒后重试");
        }
        if (left <= 0) {
            // 窗口已过，计数器归零，重新开始一轮
            attempts.remove(key(username), a);
        }
    }

    /** 记录一次失败；达到阈值时立即返回剩余锁定秒数，供日志/接口提示 */
    public void recordFailure(String username) {
        String k = key(username);
        Attempt a = attempts.computeIfAbsent(k, x -> new Attempt());
        a.firstFailedAt = System.currentTimeMillis();
        a.count.incrementAndGet();
        sweepExpired();
    }

    /** 登录成功：清空该账号的失败记录 */
    public void recordSuccess(String username) {
        attempts.remove(key(username));
        sweepExpired();
    }

    /** 供运维页展示当前被锁定的账号数，不泄露具体账号名 */
    public Map<String, Object> stats() {
        sweepExpired();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxAttempts", MAX_ATTEMPTS);
        m.put("lockSeconds", LOCK_MILLIS / 1000);
        m.put("lockedAccounts", attempts.values().stream().filter(x -> x.count.get() >= MAX_ATTEMPTS).count());
        m.put("trackedAccounts", attempts.size());
        return m;
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> now - e.getValue().firstFailedAt > LOCK_MILLIS);
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    /** 一个账号的失败窗口：首次失败时间 + 累计次数 */
    private static final class Attempt {
        private volatile long firstFailedAt = System.currentTimeMillis();
        private final AtomicInteger count = new AtomicInteger();
    }
}
