package com.cshlands.api.currency;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /currencies。MockMvc 不带 context-path，路径没有 /api。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class CurrencyControllerTest {

    private static final String NICKNAME = "currency-test-user";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    long userId;

    @BeforeEach
    void createUser() {
        jdbc.update("INSERT INTO app_user (nickname) VALUES (?)", NICKNAME);
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE nickname = ?", Long.class, NICKNAME);
    }

    @AfterEach
    void deleteUser() {
        jdbc.update("DELETE FROM app_user WHERE nickname = ?", NICKNAME);
    }

    @Test
    void listReturnsSeededCurrenciesWithCamelCaseFields() throws Exception {
        mockMvc.perform(get("/currencies").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(12)))
                .andExpect(jsonPath("$.items[?(@.code == 'JPY')].roundingUnit").value(100))
                .andExpect(jsonPath("$.items[?(@.code == 'HKD')].roundingUnit").value(1))
                .andExpect(jsonPath("$.items[?(@.code == 'JPY')].name").value("日元"));
    }

    @Test
    void storedNameIsUtf8() {
        // 排除"入库时就乱码"：直接比对库里的字节
        String hex = jdbc.queryForObject("SELECT HEX(name) FROM currency WHERE code = 'JPY'", String.class);
        assertThat(hex).isEqualTo("E697A5E58583");
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/currencies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

}
