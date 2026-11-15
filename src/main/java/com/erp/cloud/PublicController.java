package com.erp.cloud;

import com.erp.common.R;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 免登录的公开探测端点，供 Feign 客户端 / 负载均衡健康检查 / 运维脚本使用。
 * 已在 WebConfig 的鉴权拦截器里排除 {@code /api/public/**}。
 *
 * <p>注意它故意不返回任何业务数据，只回服务身份与时间，
 * 因此即使暴露在外网也不会泄露库存、金额等敏感信息。
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    @GetMapping("/ping")
    public R ping(@RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "erp-system");
        data.put("status", "UP");
        // 优先用上游透传的 traceId，否则用本过滤器生成的，方便验证链路是否打通
        data.put("traceId", traceId == null ? MDC.get("traceId") : traceId);
        data.put("serverTime", LocalDateTime.now().toString());
        return R.ok(data);
    }
}
