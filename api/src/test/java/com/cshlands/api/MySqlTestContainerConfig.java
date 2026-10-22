package com.cshlands.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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

}
