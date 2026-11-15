package com.erp.excel;

import com.erp.common.BizException;
import com.erp.common.R;
import com.erp.common.RolePermission;
import com.erp.common.TokenStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 数据导出接口。
 *
 * <p>返回的是二进制流而不是统一响应体 {@link R}——浏览器下载文件时
 * 需要的是 Content-Disposition + 正确的 MIME 类型，再包一层 JSON 反而没法直接下载。
 * 鉴权失败时仍由拦截器输出 JSON（前端据 code 处理），这与成功路径并不冲突。
 *
 * <h3>数据权限（容易被忽略的一环）</h3>
 * 导出是 GET、且路径统一挂在 /api/export 下，如果只按「是否登录」放行，
 * 仓管账号就能用 {@code /api/export/vouchers} 把财务凭证整个导走 ——
 * 相当于绕过了 /api/finance/** 的模块权限。
 * 因此这里把每种导出类型映射回它所属的业务模块，复用 {@link RolePermission}
 * 做一次与列表接口完全一致的鉴权；操作日志只对管理员开放。
 */
@Tag(name = "数据导出", description = "把订单 / 库存 / 凭证 / 往来 / 日志导出为 Excel")
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 导出类型 → 该数据所属的业务模块，用于复用既有的 RBAC 规则 */
    private static final Map<String, String> EXPORT_MODULE = Map.of(
            "purchase", "purchase",
            "sales", "sales",
            "stock", "inventory",
            "flows", "inventory",
            "vouchers", "finance",
            "arap", "settle",
            "products", "master",
            "logs", "system");

    private final ExportService service;
    private final TokenStore tokenStore;

    public ExportController(ExportService service, TokenStore tokenStore) {
        this.service = service;
        this.tokenStore = tokenStore;
    }

    /** 可导出的数据集清单（仅元数据，不含业务数据，因此不做角色过滤） */
    @Operation(summary = "可导出的数据集清单")
    @GetMapping("/types")
    public R types() {
        return R.ok(service.types());
    }

    /**
     * 下载 Excel。
     *
     * <p>中文文件名按 RFC 5987 用 {@code filename*=UTF-8''...} 编码；
     * 同时保留一个 ASCII 的 filename 兜底，兼容不认 RFC 5987 的老客户端。
     * 直接用中文原始文件名会因 HTTP 头只允许 ISO-8859-1 而变成乱码。
     */
    @Operation(summary = "导出 Excel 文件", description = "type 见 /api/export/types；需要 X-Token 请求头，并按角色校验数据权限")
    @GetMapping("/{type}")
    public ResponseEntity<byte[]> download(@PathVariable String type,
                                           @RequestHeader(value = "X-Token", required = false) String token) {
        assertExportAllowed(type, token);
        byte[] data = service.export(type);
        String encoded = URLEncoder.encode(service.fileName(type), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_MIME))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"export.xlsx\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentLength(data.length)
                .body(data);
    }

    /**
     * 按本次登录角色校验能否导出这类数据。
     * 管理员放行；其余角色把导出类型映射回业务模块，交给同一套 RBAC 判断。
     */
    private void assertExportAllowed(String type, String token) {
        TokenStore.User user = tokenStore.verify(token);
        if (user == null || "ADMIN".equals(user.role())) {
            return;
        }
        String module = EXPORT_MODULE.get(type);
        if (module == null) {
            return; // 未知类型交给 ExportService 的白名单统一拒绝
        }
        boolean allowed = !"system".equals(module)
                && RolePermission.canAccess(user.role(), "/api/" + module, "GET");
        if (!allowed) {
            throw new BizException(403, "当前角色无权导出「" + type + "」类型的数据");
        }
    }
}
