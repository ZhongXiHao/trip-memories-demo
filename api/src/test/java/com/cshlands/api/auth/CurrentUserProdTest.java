package com.cshlands.api.auth;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 没有激活 dev（即生产配置）：只带 X-User-Id、没有 token 的请求必须是 401，
 * 否则任何人改一下请求头就能冒充任何用户。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainerConfig.class)
class CurrentUserProdTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationContext context;

    @Test
    void devResolverIsNotRegistered() {
        assertThat(context.getBeansOfType(UserResolver.class)).isEmpty();
    }

    @Test
    void userIdHeaderAloneIs401EvenForExistingUser() throws Exception {
        jdbc.update("INSERT INTO app_user (id, nickname) VALUES (?, ?)", 900002L, "Amy");
        try {
            mockMvc.perform(get("/users/me").header("X-User-Id", 900002L))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        } finally {
            jdbc.update("DELETE FROM app_user WHERE id = ?", 900002L);
        }
    }

}
