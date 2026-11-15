package com.erp.cloud;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Spring Cloud 能力开关。
 *
 * <p><b>为什么要加 {@code @Profile("cloud")}？</b>
 * 依赖放进 pom 只是让类出现在 classpath 上，而 {@code @EnableFeignClients} 会真正
 * 去扫描并注册 Feign 客户端代理。如果默认模式也扫描，一旦目标服务没起来，
 * 应用启动时就可能因为找不到依赖而失败。加 profile 后：
 * 默认启动 = 纯单体，Spring Cloud 代码完全不参与；
 * 带 cloud profile 启动 = 才装配 Feign / 熔断相关 Bean。
 *
 * <p>这正是「可选增强」和「强行改造」的区别：现有功能一行不改，也不会被拖慢。
 */
@Configuration
@Profile("cloud")
@EnableFeignClients(basePackages = "com.erp.cloud")
public class CloudConfig {
}
