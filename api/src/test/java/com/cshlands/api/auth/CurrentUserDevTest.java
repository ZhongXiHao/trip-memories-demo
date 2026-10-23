package com.cshlands.api.auth;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * dev profile：X-User-Id 生效。MockMvc 不带 context-path，所以这里的路径没有 /api。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class CurrentUserDevTest {

    private static final long USER_ID = 900001L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void insertUser() {
        jdbc.update("INSERT INTO app_user (id, nickname) VALUES (?, ?)", USER_ID, "Ken");
    }

    @AfterEach
    void deleteUser() {
        jdbc.update("DELETE FROM app_user WHERE id = ?", USER_ID);
    }

    @Test
    void validHeaderResolvesCurrentUser() throws Exception {
        mockMvc.perform(get("/users/me").header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID))
                .andExpect(jsonPath("$.nickname").value("Ken"));
    }

    @Test
    void missingHeaderIs401() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void nonNumericHeaderIs401() throws Exception {
        mockMvc.perform(get("/users/me").header("X-User-Id", "ken"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unknownUserIs401() throws Exception {
        mockMvc.perform(get("/users/me").header("X-User-Id", USER_ID + 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

}
