package com.erp.common;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 密码哈希服务 —— BCrypt + 老版本 SHA-256 平滑迁移。
 *
 * <p><b>为什么换成 BCrypt</b>：SHA-256 是「快哈希」，GPU 每秒可枚举上百亿次，
 * 数据库一旦泄露极易被彩虹表/暴力破解还原。BCrypt 内置随机 salt、可调工作因子
 * （cost=10，约 100ms/次），并且每次编码结果都不同，同一密码存两份也不会看出关联。
 * 这是简历里值得讲的一个安全改造点。
 *
 * <p><b>为什么只引 spring-security-crypto</b>：本项目的鉴权是自定义的
 * Token + 拦截器（见 AuthInterceptor），如果引入 spring-boot-starter-security，
 * Spring Security 的过滤器链会自动接管所有请求，反而把现有登录逻辑挡在门外。
 * 单独引这一个轻量 jar，只借用它的 BCrypt 算法实现。
 *
 * <p><b>老库兼容</b>：历史演示库里 admin 的密码是固定盐 SHA-256（64 位十六进制）。
 * 这里不写一次性迁移脚本，而是在登录成功后调用 {@link #upgradeIfLegacy}，
 * 用本次输入的明文重新编码并回写 —— 用户无感、不需要停服、不需要强制改密。
 * 生产系统可以再加「限定过渡期，过期未升级的账号强制重置」的策略。
 */
@Service
public class PasswordService {

    /** 工作因子 10：安全与登录耗时的平衡点，约 60~120ms */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    private final Db db;

    public PasswordService(Db db) {
        this.db = db;
    }

    /** 新用户 / 重置密码时调用：每次都生成不同的 salt，结果自带算法与 cost 前缀 */
    public String encode(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BizException("密码不能为空");
        }
        // BCrypt 只处理前 72 个字节，超长部分会被静默截断，这里提前拦截避免"两个不同密码能登录"的坑
        if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new BizException("密码最长 72 字节");
        }
        return encoder.encode(raw);
    }

    /**
     * 校验密码。按存储值前缀自动判断算法：
     * $2a$ / $2b$ / $2y$ 开头是 BCrypt，其余按旧版 SHA-256 处理。
     * 这样新旧账号可以共存，回滚也不会把用户锁死。
     */
    public boolean matches(String raw, String stored) {
        if (raw == null || stored == null) {
            return false;
        }
        if (isBcrypt(stored)) {
            return encoder.matches(raw, stored);
        }
        return Hash.sha256(raw).equalsIgnoreCase(stored);
    }

    /** 登录成功后把旧哈希就地升级为 BCrypt；已是 BCrypt 则什么都不做 */
    public void upgradeIfLegacy(long userId, String raw, String stored) {
        if (stored != null && !isBcrypt(stored)) {
            db.exec("UPDATE sys_user SET password=? WHERE id=?", encode(raw), userId);
        }
    }

    public boolean isLegacy(String stored) {
        return stored != null && !isBcrypt(stored);
    }

    private static boolean isBcrypt(String stored) {
        return stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$");
    }
}
