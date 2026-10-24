package com.cshlands.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 测试用的真 MySQL 8：直接执行仓库根目录 db/init 下的 DDL 和种子数据，
 * 并和生产约定一致把服务器时区设成 UTC。
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestContainerConfig {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysql() {
        MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
        mysql.withDatabaseName("trip_memories");
        mysql.withCommand("--default-time-zone=+00:00");
        mysql.withCopyFileToContainer(MountableFile.forHostPath("../db/init/schema.sql"),
                "/docker-entrypoint-initdb.d/01-schema.sql");
        mysql.withCopyFileToContainer(MountableFile.forHostPath("../db/init/data.sql"),
                "/docker-entrypoint-initdb.d/02-data.sql");
        return mysql;
    }

    /**
     * @ServiceConnection 生成的 JDBC URL 不带参数。这里补上 application.yaml 里的
     * connectionTimeZone=UTC，否则驱动按 JVM 本地时区解释 DATETIME，读出的时间会错几个小时。
     */
    @Bean
    DynamicPropertyRegistrar connectionTimeZone() {
        return registry -> {
            registry.add("spring.datasource.hikari.data-source-properties.connectionTimeZone", () -> "UTC");
            registry.add("spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession",
                    () -> "true");
        };
    }

}
