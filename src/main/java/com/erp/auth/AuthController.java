package com.erp.auth;

import com.erp.common.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口：登录 / 退出 / 当前用户。
 *
 * <p>安全要点（改动记录）：
 * 1. 密码校验交给 {@link PasswordService}，BCrypt 慢哈希 + 老 SHA-256 自动升级；
 * 2. 登录前置 {@link LoginAttemptService}，连续失败锁定，避免被撞库；
 * 3. 入参改用 {@link LoginRequest} DTO + @Valid，判空逻辑不再写死在 Controller 里。
 */
@Tag(name = "认证与会话", description = "登录、退出、当前登录用户与菜单权限")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final Db db;
    private final TokenStore tokenStore;
    private final LogService logService;
    private final PasswordService passwordService;
    private final LoginAttemptService loginAttemptService;

    public AuthController(Db db, TokenStore tokenStore, LogService logService,
                          PasswordService passwordService, LoginAttemptService loginAttemptService) {
        this.db = db;
        this.tokenStore = tokenStore;
        this.logService = logService;
        this.passwordService = passwordService;
        this.loginAttemptService = loginAttemptService;
    }

    @Operation(summary = "账号密码登录", description = "成功后返回 token、角色与可见菜单；连续失败 5 次锁定 5 分钟")
    @PostMapping("/login")
    public R login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String username = req.getUsername().trim();
        String password = req.getPassword();

        // 前置锁定检查：被锁的账号连数据库都不查，减少无效开销
        loginAttemptService.assertNotLocked(username);

        Map<String, Object> row = db.one("SELECT * FROM sys_user WHERE username=?", username);
        String stored = row == null ? null : String.valueOf(row.get("password"));
        if (row == null || !passwordService.matches(password, stored)) {
            loginAttemptService.recordFailure(username);
            // 无论「用户不存在」还是「密码错误」都返回同一句话，不泄露账号是否存在
            throw new BizException("用户名或密码错误");
        }
        if (toInt(row.get("status")) != 1) {
            throw new BizException("该账号已被停用");
        }

        // 登录成功：清失败计数 + 把老库的 SHA-256 哈希无感升级成 BCrypt
        loginAttemptService.recordSuccess(username);
        long userId = ((Number) row.get("id")).longValue();
        passwordService.upgradeIfLegacy(userId, password, stored);

        String realName = row.get("real_name") == null ? username : String.valueOf(row.get("real_name"));
        String role = row.get("role") == null ? "ADMIN" : String.valueOf(row.get("role"));
        String token = tokenStore.create(new TokenStore.User(username, realName, role));

        UserContext.set(username);
        logService.record("登录", "用户登录系统，来源 IP " + clientIp(request));
        UserContext.clear();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("username", username);
        data.put("realName", realName);
        data.put("role", role);
        // 菜单权限随登录结果一起下发，前端据此渲染侧边栏（后端仍会独立校验接口权限）
        data.put("permissions", RolePermission.menus(role));
        return R.ok(data);
    }

    @PostMapping("/logout")
    public R logout(@RequestHeader(value = "X-Token", required = false) String token) {
        UserContext.set("unknown");
        logService.record("退出", "用户退出登录");
        UserContext.clear();
        tokenStore.remove(token);
        return R.ok();
    }

    @GetMapping("/me")
    public R me() {
        String username = UserContext.get();
        Map<String, Object> row = db.one("SELECT username, real_name, role FROM sys_user WHERE username=?", username);
        if (row == null) {
            throw new BizException(401, "登录状态已失效");
        }
        // 页面刷新时重新下发权限，避免 localStorage 里的旧权限与数据库角色不一致
        Map<String, Object> data = new LinkedHashMap<>(row);
        String role = row.get("role") == null ? "ADMIN" : String.valueOf(row.get("role"));
        data.put("permissions", RolePermission.menus(role));
        return R.ok(data);
    }

    /** 优先取反向代理透传的真实 IP；没有时用直连地址 */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static int toInt(Object v) {
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
