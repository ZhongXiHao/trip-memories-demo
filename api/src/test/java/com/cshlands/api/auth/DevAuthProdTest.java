package com.cshlands.api.auth;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 没有激活 dev：注册接口根本不存在，不是"存在但拒绝"。
 * 否则任何人不用登录就能无限创建用户。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainerConfig.class)
class DevAuthProdTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void registerIsNotMappedOutsideDevProfile() throws Exception {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"intruder\"}"))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(before);
    }

}
