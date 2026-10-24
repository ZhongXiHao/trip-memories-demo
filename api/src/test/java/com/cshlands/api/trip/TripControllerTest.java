package com.cshlands.api.trip;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips。MockMvc 不带 context-path，路径没有 /api。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class TripControllerTest {

    private static final String NICKNAME = "trip-test-user";

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
    void deleteData() {
        jdbc.update("DELETE FROM trip_member WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM trip WHERE created_by = ?", userId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    private ResultActions createTrip(String json) throws Exception {
        return mockMvc.perform(post("/trips")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static final String VALID = """
            {"name": "東京五日遊", "displayCurrency": "HKD", "timezone": "Asia/Tokyo",
             "startDate": "2026-10-01", "endDate": "2026-10-05"}""";

    @Test
    void createReturnsTripWithCreatorAsOwner() throws Exception {
        String body = createTrip(VALID)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("東京五日遊"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.startDate").value("2026-10-01"))
                .andExpect(jsonPath("$.endDate").value("2026-10-05"))
                .andExpect(jsonPath("$.timezone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.displayCurrency").value("HKD"))
                .andExpect(jsonPath("$.myRole").value("owner"))
                .andReturn().getResponse().getContentAsString();

        // createdAt 是库里生成的 UTC 时间，带 Z，并且就是"现在"（时区读错会差几个小时）
        String createdAt = JsonPath.read(body, "$.createdAt");
        assertThat(createdAt).endsWith("Z");
        assertThat(Duration.between(Instant.parse(createdAt), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));

        Number tripId = JsonPath.read(body, "$.id");
        Number memberId = JsonPath.read(body, "$.myMemberId");
        Map<String, Object> member = jdbc.queryForMap(
                "SELECT id, user_id, role, status FROM trip_member WHERE trip_id = ?", tripId.longValue());
        assertThat(member.get("id")).isEqualTo(memberId.longValue());
        assertThat(member.get("user_id")).isEqualTo(userId);
        assertThat(member.get("role")).isEqualTo("owner");
        assertThat(member.get("status")).isEqualTo("active");
    }

    @Test
    void clientCannotChooseIdOrCreator() throws Exception {
        String body = createTrip("""
                {"id": 999999, "createdBy": 1, "name": "x", "displayCurrency": "HKD", "timezone": "UTC"}""")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Number tripId = JsonPath.read(body, "$.id");
        assertThat(tripId.longValue()).isNotEqualTo(999999L);
        assertThat(jdbc.queryForObject("SELECT created_by FROM trip WHERE id = ?", Long.class, tripId.longValue()))
                .isEqualTo(userId);
    }

    @Test
    void unknownCurrencyIsValidationFailed() throws Exception {
        createTrip("""
                {"name": "x", "displayCurrency": "XXX", "timezone": "UTC"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("displayCurrency"));
        assertNoTripCreated();
    }

    @Test
    void invalidTimezoneIsValidationFailed() throws Exception {
        createTrip("""
                {"name": "x", "displayCurrency": "HKD", "timezone": "Mars/Base"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("timezone"));
        assertNoTripCreated();
    }

    @Test
    void endDateBeforeStartDateIsValidationFailed() throws Exception {
        createTrip("""
                {"name": "x", "displayCurrency": "HKD", "timezone": "UTC",
                 "startDate": "2026-10-05", "endDate": "2026-10-01"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("endDate"));
        assertNoTripCreated();
    }

    @Test
    void missingRequiredFieldsAreListedByName() throws Exception {
        String body = createTrip("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();

        List<String> fields = JsonPath.read(body, "$.fields[*].field");
        assertThat(fields).containsExactlyInAnyOrder("name", "displayCurrency", "timezone");
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/trips").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private void assertNoTripCreated() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip WHERE created_by = ?", Integer.class, userId))
                .isZero();
    }

}
