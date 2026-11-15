package com.erp.cloud;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 演示 Spring Cloud 的动态配置刷新能力（{@code @RefreshScope}）。
 *
 * <p>普通 {@code @Value} 注入的值在应用启动后就固定了。标了 {@code @RefreshScope} 的 Bean
 * 是代理对象：调用 {@code POST /actuator/refresh} 后，Spring 会重建它并重新绑定配置，
 * 无需重启应用。接上 Spring Cloud Config Server / Nacos 后，就变成真正的配置中心热更新。
 *
 * <p>在当前单体里可以用它直观验证「配置与代码解耦」：修改外部配置文件后调 refresh，
 * 运维端点返回的新值立即生效，而业务 Bean 完全不受影响。
 */
@Component
@RefreshScope
@Profile("cloud")
public class OpsProperties {

    @Value("${erp.cloud.probe-enabled:true}")
    private boolean probeEnabled;

    @Value("${erp.internal.base-url}")
    private String baseUrl;

    public boolean isProbeEnabled() {
        return probeEnabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
