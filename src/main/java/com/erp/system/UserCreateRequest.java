package com.erp.system;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 新增用户请求 DTO。
 *
 * <p>把「格式约束」从 Service 的 if 判断里搬到 Bean Validation 注解上：
 * Controller 加一个 {@code @Valid}，非法输入在进入业务层之前就被拦截，
 * 并由 GlobalExceptionHandler 统一返回「哪个字段、为什么错」。
 *
 * <p>{@code @JsonAlias} 是为了兼容前端既有的 snake_case 字段名（real_name），
 * 接口升级不需要前端同步改名。
 */
public class UserCreateRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 32, message = "用户名长度需在 2-32 位")
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 72, message = "密码长度需在 6-72 位")
    private String password;

    @NotBlank(message = "角色不能为空")
    private String role;

    @Size(max = 64, message = "姓名最长 64 位")
    @JsonAlias("real_name")
    private String realName;

    /** 转成现有 UserService 接受的 Map，避免为了一个 DTO 重写整条业务链路 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("password", password);
        m.put("role", role);
        m.put("real_name", realName == null ? "" : realName);
        return m;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }
}
