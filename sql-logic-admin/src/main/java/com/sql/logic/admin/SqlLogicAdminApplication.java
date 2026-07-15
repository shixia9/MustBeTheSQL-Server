package com.sql.logic.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "com.sql.logic.admin")
@EnableDiscoveryClient
public class SqlLogicAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlLogicAdminApplication.class, args);
    }
}
