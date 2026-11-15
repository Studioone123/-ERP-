package com.erp.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi 接口文档配置。
 *
 * <p>启动后访问：
 * <ul>
 *   <li>{@code /swagger-ui/index.html} —— 可视化接口页，可直接发请求调试</li>
 *   <li>{@code /v3/api-docs} —— OpenAPI 3 的 JSON，可导入 Postman / Apifox 或生成客户端 SDK</li>
 * </ul>
 *
 * <p>本项目用的是自定义 {@code X-Token} 请求头鉴权（不是 OAuth2 / JWT Bearer），
 * 所以这里声明一个 APIKEY 类型的安全方案。配置后 Swagger 页面右上角会出现
 * 「Authorize」按钮，填入登录返回的 token 后，页面上所有 Try it out 请求都会自动带上该请求头。
 * 这比让使用者手动给每个接口加 header 方便得多，也是团队里接口文档能被真正用起来的关键。
 */
@Configuration
public class OpenApiConfig {

    public static final String TOKEN_SCHEME = "X-Token";

    @Bean
    public OpenAPI erpOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("汇智 ERP 开放接口文档")
                        .version("1.0.0")
                        .description("进销存 + 业财一体化 ERP 的 REST 接口。"
                                + "除登录与 /api/public/** 外，所有接口都需要在请求头携带 X-Token。"
                                + "接口按角色鉴权，非管理员角色对系统管理模块只有读权限。")
                        .contact(new Contact().name("ERP 项目组")))
                .addSecurityItem(new SecurityRequirement().addList(TOKEN_SCHEME))
                .components(new Components().addSecuritySchemes(TOKEN_SCHEME,
                        new SecurityScheme()
                                .name(TOKEN_SCHEME)
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .description("调用 /api/auth/login 后返回的 token")));
    }
}
