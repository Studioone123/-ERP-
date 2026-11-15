package com.erp.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求 DTO —— 用声明式校验替代 Controller 里的 if 判空。
 *
 * <p>为什么单独建 DTO 而不是继续用 {@code Map<String,Object>}：
 * Map 无法携带字段约束，校验逻辑只能散落在各处；DTO + Bean Validation
 * 让「什么算合法输入」和「业务规则」分离，接口文档（springdoc）也能自动推导出字段与约束。
 *
 * <p>这里刻意只做「非空 + 长度上限」，不限制密码最小长度：
 * 密码太短应该由「用户名或密码错误」统一返回，避免给撞库者反馈「密码长度不对」这种信息。
 */
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名最长 64 位")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 72, message = "密码最长 72 字节")
    private String password;

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
}
