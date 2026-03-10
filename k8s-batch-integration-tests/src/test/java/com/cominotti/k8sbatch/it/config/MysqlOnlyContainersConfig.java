package com.cominotti.k8sbatch.it.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class MysqlOnlyContainersConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return SharedContainersConfig.MYSQL;
    }
}
