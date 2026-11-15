package com.erp.cloud;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * 声明式 HTTP 客户端（OpenFeign）—— Spring Cloud 里最常用、也最容易落地的一个组件。
 *
 * <p><b>它解决什么问题</b>：不用手写 RestTemplate / HttpClient 的拼 URL、设头、解析响应，
 * 只要声明一个接口，Spring 在运行时生成代理。超时、日志、重试、负载均衡都由配置驱动。
 *
 * <p><b>为什么这里用 {@code url} 而不是服务名</b>：
 * 真实微服务里通常写 {@code @FeignClient(name = "erp-report")} 并配合注册中心做服务发现。
 * 本项目还没有注册中心（单体改造不宜一步到位），所以先用 url 直连，
 * 由 {@code erp.internal.base-url} 配置。将来把报表模块拆成独立服务，
 * 只需把这个配置改成新服务地址，或换成 name + 注册中心，调用方代码零改动 ——
 * 这就是「先建立调用边界，再拆部署单元」的渐进式演进思路。
 *
 * <p>当前目标是本服务的公开探测端点，用来演示完整调用链（含 traceId 透传与熔断）。
 */
@FeignClient(name = "erp-internal", url = "${erp.internal.base-url}")
public interface ErpInternalClient {

    /**
     * 探测目标服务是否可用。
     * 把上层请求的 traceId 放在请求头里传下去，日志就能串成一条链。
     */
    @GetMapping("/api/public/ping")
    Map<String, Object> ping(@RequestHeader("X-Trace-Id") String traceId);
}
