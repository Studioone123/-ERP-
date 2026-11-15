package com.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 汇智 ERP 启动类
 * 启动后访问 http://localhost:8080
 */
@SpringBootApplication
@ConfigurationPropertiesScan // 扫描 erp.ai 等 @ConfigurationProperties 配置类（AiProperties）
public class ErpApplication {
    public static void main(String[] args) {
        SpringApplication.run(ErpApplication.class, args);
    }
}
