package com.erp.cloud;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.MDC;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Feign 调用 + Resilience4J 熔断的组合演示。
 *
 * <p><b>熔断的意义</b>：如果下游服务挂了或响应极慢，调用方线程会被大量占满，
 * 最终把整个系统拖垮（雪崩）。熔断器在连续失败达到阈值后「跳闸」，
 * 后续请求直接走降级逻辑，不再真正发起网络调用；过一段时间进入半开状态试探恢复。
 *
 * <p>这里用 {@code CircuitBreakerFactory#create("erpInternal")} 包裹 Feign 调用，
 * 实例名与 application-cloud.yml 里 resilience4j.circuitbreaker.instances 下的名字一致。
 * 想观察效果：把 {@code erp.internal.base-url} 改成一个不通的地址，
 * 连续调用几次 /api/ops/cloud-probe，就能看到 circuitState 从 CLOSED 变成 OPEN，
 * 且返回 fallbackTriggered=true。
 */
@Service
@Profile("cloud")
public class CloudProbeService {

    private final ErpInternalClient client;
    private final CircuitBreakerFactory<?, ?> breakerFactory;
    private final CircuitBreakerRegistry registry;
    private final OpsProperties properties;

    public CloudProbeService(ErpInternalClient client,
                             CircuitBreakerFactory<?, ?> breakerFactory,
                             CircuitBreakerRegistry registry,
                             OpsProperties properties) {
        this.client = client;
        this.breakerFactory = breakerFactory;
        this.registry = registry;
        this.properties = properties;
    }

    /** 真正发一次 Feign 调用，并把熔断状态、是否降级、traceId 一并返回 */
    public Map<String, Object> probe() {
        String traceId = Optional.ofNullable(MDC.get("traceId")).orElse("cloud-probe");
        AtomicReference<String> failure = new AtomicReference<>();
        Map<String, Object> out = new LinkedHashMap<>();

        CircuitBreaker breaker = breakerFactory.create("erpInternal");
        try {
            Map<String, Object> response = breaker.run(
                    () -> client.ping(traceId),
                    throwable -> {
                        // 降级函数：下游不可用时不再抛异常，而是返回一份可识别的兜底结果
                        failure.set(throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                        return Map.<String, Object>of("fallback", true, "reason", "下游服务暂不可用");
                    });
            out.put("response", response);
        } catch (Exception e) {
            failure.set(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        out.put("fallbackTriggered", failure.get() != null);
        out.put("failure", failure.get());
        out.put("circuitState", registry.circuitBreaker("erpInternal").getState().name());
        out.put("target", properties.getBaseUrl());
        out.put("traceId", traceId);
        out.put("probeEnabled", properties.isProbeEnabled());
        return out;
    }

    /** 不发起调用，只返回当前组件状态，供运维页面展示 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("feignClient", "erp-internal");
        m.put("target", properties.getBaseUrl());
        m.put("circuitState", registry.circuitBreaker("erpInternal").getState().name());
        m.put("probeEnabled", properties.isProbeEnabled());
        return m;
    }
}
