package com.erp.system;

import com.erp.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 用户与角色管理 —— 对应金蝶「系统管理 → 用户管理 / 角色授权」。
 *
 * <p>密码统一经 {@link PasswordService} 做 BCrypt 慢哈希后落库，明文绝不入库；
 * 历史 SHA-256 账号在登录成功时自动升级，无需用户改密（见 AuthController）。
 * 用户列表刻意不 SELECT password 字段，避免敏感哈希经接口外泄。
 * 用户被停用后，已签发的 token 仍可能在内存里有效，因此登录接口会再次查库校验
 * status（见 AuthController.login），避免「停用了还能继续用」。
 */
@Service
public class UserService {

    private final Db db;
    private final LogService logService;
    private final PasswordService passwordService;

    public UserService(Db db, LogService logService, PasswordService passwordService) {
        this.db = db;
        this.logService = logService;
        this.passwordService = passwordService;
    }

    /** 用户列表（不返回密码字段，避免敏感信息外泄） */
    public List<Map<String, Object>> list(String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, username, real_name, role, status, created_at FROM sys_user WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (username LIKE ? OR real_name LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            Collections.addAll(args, kw, kw);
        }
        sql.append(" ORDER BY id");
        return db.list(sql.toString(), args.toArray());
    }

    /** 角色字典，供前端下拉 */
    public List<Map<String, Object>> roles() {
        List<Map<String, Object>> list = new ArrayList<>();
        RolePermission.ROLES.forEach((code, name) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", name);
            m.put("menus", RolePermission.menus(code));
            list.add(m);
        });
        return list;
    }

    @Transactional
    public long create(Map<String, Object> body) {
        String username = Db.str(body, "username");
        String password = Db.str(body, "password");
        String role = Db.str(body, "role");
        if (username.isEmpty() || password.isEmpty()) {
            throw new BizException("用户名和密码不能为空");
        }
        if (password.length() < 6) {
            throw new BizException("密码长度不能少于 6 位");
        }
        RolePermission.assertRole(role);
        Integer exists = db.value("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, username);
        if (exists != null && exists > 0) {
            throw new BizException("用户名 " + username + " 已存在");
        }
        long id = db.insert("INSERT INTO sys_user(username,password,real_name,role,status) VALUES(?,?,?,?,1)",
                username, passwordService.encode(password), defaultString(Db.str(body, "real_name"), username), role);
        logService.record("新增用户", username + " / " + role);
        return id;
    }

    /** 调整角色：重新登录后新权限才生效（token 里缓存了角色） */
    @Transactional
    public void updateRole(long id, String role) {
        RolePermission.assertRole(role);
        require(id);
        db.exec("UPDATE sys_user SET role=? WHERE id=?", role, id);
        logService.record("调整用户角色", "ID " + id + " → " + role);
    }

    /** 启用 / 停用；内置 admin 不允许停用，防止把自己锁在系统外面 */
    @Transactional
    public void updateStatus(long id, int status) {
        Map<String, Object> row = require(id);
        if (status != 0 && status != 1) {
            throw new BizException("状态只能是 0 或 1");
        }
        if (status == 0 && "admin".equals(Val.strOf(row.get("username")))) {
            throw new BizException("内置管理员账号不能停用");
        }
        db.exec("UPDATE sys_user SET status=? WHERE id=?", status, id);
        logService.record(status == 1 ? "启用用户" : "停用用户", Val.strOf(row.get("username")));
    }

    @Transactional
    public void resetPassword(long id, String password) {
        Map<String, Object> row = require(id);
        if (password == null || password.length() < 6) {
            throw new BizException("新密码长度不能少于 6 位");
        }
        db.exec("UPDATE sys_user SET password=? WHERE id=?", passwordService.encode(password), id);
        logService.record("重置用户密码", Val.strOf(row.get("username")));
    }

    private Map<String, Object> require(long id) {
        Map<String, Object> row = db.one("SELECT * FROM sys_user WHERE id=?", id);
        if (row == null) {
            throw new BizException("用户不存在");
        }
        return row;
    }

    private static String defaultString(String v, String fallback) {
        return v.isEmpty() ? fallback : v;
    }
}
