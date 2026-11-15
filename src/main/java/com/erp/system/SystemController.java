package com.erp.system;

import com.erp.common.PageSupport;
import com.erp.common.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 系统管理接口：用户、角色、启停与密码重置（金蝶「系统管理」模块） */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final UserService service;

    public SystemController(UserService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public R users(@RequestParam(required = false) String keyword,
                   @RequestParam(required = false) Integer page,
                   @RequestParam(required = false) Integer size) {
        return PageSupport.reply(service.list(keyword), page, size);
    }

    /** 角色字典：编码、名称、该角色可见的菜单 */
    @GetMapping("/roles")
    public R roles() {
        return R.ok(service.roles());
    }

    /** 新增用户：用户名格式、密码长度、角色非空由 @Valid 在进入 Service 前统一校验 */
    @PostMapping("/users")
    public R create(@Valid @RequestBody UserCreateRequest req) {
        return R.ok(Map.of("id", service.create(req.toMap())));
    }

    /** body: {"role":"FINANCE"} */
    @PutMapping("/users/{id}/role")
    public R updateRole(@PathVariable long id, @RequestBody Map<String, Object> body) {
        service.updateRole(id, body.get("role") == null ? "" : String.valueOf(body.get("role")).trim());
        return R.ok();
    }

    /** body: {"status":0} */
    @PutMapping("/users/{id}/status")
    public R updateStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        int status = 1;
        try {
            status = Integer.parseInt(String.valueOf(body.getOrDefault("status", 1)));
        } catch (NumberFormatException ignored) {
            // 非法值按启用处理，由 Service 再次校验
        }
        service.updateStatus(id, status);
        return R.ok();
    }

    /** body: {"password":"newpass123"} */
    @PutMapping("/users/{id}/password")
    public R resetPassword(@PathVariable long id, @RequestBody Map<String, Object> body) {
        service.resetPassword(id, body.get("password") == null ? "" : String.valueOf(body.get("password")));
        return R.ok();
    }
}
