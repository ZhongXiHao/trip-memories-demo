package com.cshlands.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MySqlTestContainerConfig.class)
class ApiApplicationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoads() {
    }

    @Test
    void ddlAndSeedDataAreLoadedInUtc() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM currency", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT @@global.time_zone", String.class)).isEqualTo("+00:00");
    }

}
