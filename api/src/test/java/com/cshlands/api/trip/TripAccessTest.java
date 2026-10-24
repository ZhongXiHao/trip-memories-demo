package com.cshlands.api.trip;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /trips、GET/PUT /trips/{tripId}、POST /trips/{tripId}/delete 的权限和行为。
 * 成员、行程直接用 JDBC 造，不依赖成员接口。MockMvc 不带 context-path，路径没有 /api。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class TripAccessTest {

    private static final String REPLACEMENT = """
            {"name": "大阪三日遊", "description": "改了", "displayCurrency": "JPY", "timezone": "Asia/Tokyo",
             "startDate": "2026-11-01", "endDate": "2026-11-03"}""";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void deleteData() {
        for (long userId : userIds) {
            jdbc.update("DELETE FROM trip_member WHERE user_id = ?", userId);
        }
        for (long userId : userIds) {
            jdbc.update("DELETE FROM trip WHERE created_by = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    // ---- 造数据 ----

    private long user(String nickname) {
        jdbc.update("INSERT INTO app_user (nickname) VALUES (?)", nickname);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM app_user", Long.class);
        userIds.add(id);
        return id;
    }

    private long trip(long ownerId, String name, String createdAt) {
        jdbc.update("INSERT INTO trip (name, timezone, display_currency, created_by, created_at) "
                + "VALUES (?, 'Asia/Tokyo', 'HKD', ?, ?)", name, ownerId, createdAt);
        long tripId = jdbc.queryForObject("SELECT MAX(id) FROM trip", Long.class);
        member(tripId, ownerId, "owner", "active");
        return tripId;
    }

    private void member(long tripId, long userId, String role, String status) {
        jdbc.update("INSERT INTO trip_member (trip_id, user_id, role, status) VALUES (?, ?, ?, ?)",
                tripId, userId, role, status);
    }

    private ResultActions getTrip(long userId, long tripId) throws Exception {
        return mockMvc.perform(get("/trips/" + tripId).header("X-User-Id", userId));
    }

    private ResultActions putTrip(long userId, long tripId, String json) throws Exception {
        return mockMvc.perform(put("/trips/" + tripId).header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions deleteTrip(long userId, long tripId) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/delete").header("X-User-Id", userId));
    }

    // ---- GET /trips/{tripId} ----

    @Test
    void ownerAndMemberCanGetTripWithTheirOwnRole() throws Exception {
        long owner = user("t-owner");
        long member = user("t-member");
        long tripId = trip(owner, "東京", "2026-01-01 00:00:00");
        member(tripId, member, "member", "active");

        getTrip(owner, tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tripId))
                .andExpect(jsonPath("$.name").value("東京"))
                .andExpect(jsonPath("$.myRole").value("owner"))
                .andExpect(jsonPath("$.memberCount").value(2));
        getTrip(member, tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("member"))
                .andExpect(jsonPath("$.memberCount").value(2));
    }

    @Test
    void memberCountOnlyCountsActiveMembers() throws Exception {
        long owner = user("t-owner");
        long active = user("t-active");
        long leaver = user("t-leaver");
        long tripId = trip(owner, "東京", "2026-01-01 00:00:00");
        member(tripId, active, "member", "active");
        member(tripId, leaver, "member", "left");

        // 负责人 + 一个在籍成员 = 2；已退出的不算
        getTrip(owner, tripId).andExpect(jsonPath("$.memberCount").value(2));
    }

    @Test
    void nonMemberLeftMemberAndMissingTripAreAllNotFound() throws Exception {
        long owner = user("t-owner");
        long stranger = user("t-stranger");
        long leaver = user("t-leaver");
        long tripId = trip(owner, "東京", "2026-01-01 00:00:00");
        member(tripId, leaver, "member", "left");

        for (long userId : new long[]{stranger, leaver}) {
            getTrip(userId, tripId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        getTrip(owner, tripId + 1000).andExpect(status().isNotFound());
    }

    @Test
    void nonNumericTripIdIsValidationFailed() throws Exception {
        long owner = user("t-owner");
        mockMvc.perform(get("/trips/abc").header("X-User-Id", owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("tripId"));
    }

    // ---- PUT /trips/{tripId} ----

    @Test
    void ownerReplacesAllEditableFieldsAndCreatedAtStays() throws Exception {
        long owner = user("t-owner");
        long tripId = trip(owner, "東京", "2026-01-01 00:00:00");
        jdbc.update("UPDATE trip SET description = 'old', start_date = '2026-10-01' WHERE id = ?", tripId);

        putTrip(owner, tripId, REPLACEMENT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("大阪三日遊"))
                .andExpect(jsonPath("$.description").value("改了"))
                .andExpect(jsonPath("$.displayCurrency").value("JPY"))
                .andExpect(jsonPath("$.startDate").value("2026-11-01"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"));

        // 整体替换：没传的可选字段会被清空，不是保留旧值
        putTrip(owner, tripId, """
                {"name": "只剩名字", "displayCurrency": "HKD", "timezone": "UTC"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.startDate").doesNotExist())
                .andExpect(jsonPath("$.endDate").doesNotExist());
        getTrip(owner, tripId).andExpect(jsonPath("$.name").value("只剩名字"));
    }

    @Test
    void plainMemberCannotUpdateAndNonMemberGetsNotFound() throws Exception {
        long owner = user("t-owner");
        long member = user("t-member");
        long stranger = user("t-stranger");
        long tripId = trip(owner, "東京", "2026-01-01 00:00:00");
        member(tripId, member, "member", "active");

        putTrip(member, tripId, REPLACEMENT)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        putTrip(stranger, tripId, REPLACEMENT)
                .andExpect(status().isNotFound());

        getTrip(owner, tripId).andExpect(jsonPath("$.name").value("東京"));
    }

    @Test
    void updateValidatesLikeCreateAndKeepsOldValuesOnFailure() throws Exception {
        long owner = user("t-owner");
        long tripId = trip(owner, "東京", "2026-01-01 00:00:00");

        putTrip(owner, tripId, """
                {"name": "x", "displayCurrency": "XXX", "timezone": "UTC"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("displayCurrency"));
        putTrip(owner, tripId, """
                {"name": "x", "displayCurrency": "HKD", "timezone": "UTC",
                 "startDate": "2026-10-05", "endDate": "2026-10-01"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("endDate"));
        putTrip(owner, tripId, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        getTrip(owner, tripId).andExpect(jsonPath("$.name").value("東京"));
    }

    // ---- POST /trips/{tripId}/delete ----

    @Test
    void ownerSoftDeletesTripAndEverythingAfterwardsIsNotFound() throws Exception {
        long owner = user("t-owner");
        long member = user("t-member");
        long tripId = trip(owner, "東京", "2026-01-01 00:00:00");
        member(tripId, member, "member", "active");

        deleteTrip(owner, tripId).andExpect(status().isNoContent());

        getTrip(owner, tripId).andExpect(status().isNotFound());
        getTrip(member, tripId).andExpect(status().isNotFound());
        putTrip(owner, tripId, REPLACEMENT).andExpect(status().isNotFound());
        deleteTrip(owner, tripId).andExpect(status().isNotFound());
        mockMvc.perform(get("/trips").header("X-User-Id", owner))
                .andExpect(jsonPath("$.items", hasSize(0)));

        // 软删除：行和成员都还在，只是打了删除时间
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM trip WHERE id = ?", Boolean.class, tripId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_member WHERE trip_id = ?", Integer.class, tripId))
                .isEqualTo(2);
    }

    @Test
    void plainMemberCannotDeleteAndNonMemberGetsNotFound() throws Exception {
        long owner = user("t-owner");
        long member = user("t-member");
        long stranger = user("t-stranger");
        long tripId = trip(owner, "東京", "2026-01-01 00:00:00");
        member(tripId, member, "member", "active");

        deleteTrip(member, tripId).andExpect(status().isForbidden());
        deleteTrip(stranger, tripId).andExpect(status().isNotFound());

        getTrip(owner, tripId).andExpect(status().isOk());
    }

    // ---- GET /trips ----

    @Test
    void listReturnsOnlyMyActiveTripsNewestFirst() throws Exception {
        long me = user("t-me");
        long other = user("t-other");

        long oldest = trip(me, "最早", "2026-01-01 00:00:00");
        long joined = trip(other, "被邀请的", "2026-03-01 00:00:00");
        member(joined, me, "member", "active");
        long newest = trip(me, "最新", "2026-05-01 00:00:00");

        long left = trip(other, "我退出了的", "2026-04-01 00:00:00");
        member(left, me, "member", "left");
        trip(other, "别人的", "2026-04-02 00:00:00");
        long deleted = trip(me, "已删除", "2026-06-01 00:00:00");
        jdbc.update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", deleted);

        mockMvc.perform(get("/trips").header("X-User-Id", me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].id").value(newest))
                .andExpect(jsonPath("$.items[0].myRole").value("owner"))
                .andExpect(jsonPath("$.items[0].memberCount").value(1))
                .andExpect(jsonPath("$.items[1].id").value(joined))
                .andExpect(jsonPath("$.items[1].myRole").value("member"))
                .andExpect(jsonPath("$.items[1].memberCount").value(2))
                .andExpect(jsonPath("$.items[2].id").value(oldest))
                .andExpect(jsonPath("$.items[2].memberCount").value(1));
    }

    @Test
    void listIsEmptyItemsWhenNoTrips() throws Exception {
        long me = user("t-me");
        mockMvc.perform(get("/trips").header("X-User-Id", me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void allTripEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/trips")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/trips/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/trips/1").contentType(MediaType.APPLICATION_JSON).content(REPLACEMENT))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/trips/1/delete")).andExpect(status().isUnauthorized());
    }

}
