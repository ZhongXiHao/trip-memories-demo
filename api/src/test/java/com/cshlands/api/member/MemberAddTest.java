package com.cshlands.api.member;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips/{tripId}/members。行程、成员直接用 JDBC 造。MockMvc 不带 context-path，路径没有 /api。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class MemberAddTest {

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

    private long trip(long ownerId) {
        jdbc.update("INSERT INTO trip (name, timezone, display_currency, created_by) "
                + "VALUES ('東京', 'Asia/Tokyo', 'HKD', ?)", ownerId);
        long tripId = jdbc.queryForObject("SELECT MAX(id) FROM trip", Long.class);
        member(tripId, ownerId, "owner", "active");
        return tripId;
    }

    private long member(long tripId, long userId, String role, String status) {
        jdbc.update("INSERT INTO trip_member (trip_id, user_id, role, status) VALUES (?, ?, ?, ?)",
                tripId, userId, role, status);
        return jdbc.queryForObject("SELECT id FROM trip_member WHERE trip_id = ? AND user_id = ?",
                Long.class, tripId, userId);
    }

    private ResultActions add(long actingUserId, long tripId, String json) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/members")
                .header("X-User-Id", actingUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions add(long actingUserId, long tripId, long userId) throws Exception {
        return add(actingUserId, tripId, "{\"userId\": " + userId + "}");
    }

    private int memberRows(long tripId, long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trip_member WHERE trip_id = ? AND user_id = ?",
                Integer.class, tripId, userId);
    }

    // ---- 成功路径 ----

    @Test
    void ownerAddsNewUserAsActiveMember() throws Exception {
        long owner = user("m-owner");
        long newcomer = user("m-newcomer");
        long tripId = trip(owner);

        String body = add(owner, tripId, newcomer)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(newcomer))
                .andExpect(jsonPath("$.nickname").value("m-newcomer"))
                .andExpect(jsonPath("$.role").value("member"))
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();

        String joinedAt = JsonPath.read(body, "$.joinedAt");
        assertThat(joinedAt).endsWith("Z");
        Number memberId = JsonPath.read(body, "$.memberId");
        assertThat(jdbc.queryForObject("SELECT id FROM trip_member WHERE trip_id = ? AND user_id = ?",
                Long.class, tripId, newcomer)).isEqualTo(memberId.longValue());
    }

    @Test
    void formerMemberRejoinsWithSameMemberIdAndStatus200() throws Exception {
        long owner = user("m-owner");
        long returning = user("m-returning");
        long tripId = trip(owner);
        long oldMemberId = member(tripId, returning, "member", "left");

        add(owner, tripId, returning)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(oldMemberId))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.role").value("member"));

        assertThat(memberRows(tripId, returning)).isEqualTo(1);
    }

    // ---- 冲突和校验 ----

    @Test
    void alreadyActiveMemberIsConflict() throws Exception {
        long owner = user("m-owner");
        long existing = user("m-existing");
        long tripId = trip(owner);
        member(tripId, existing, "member", "active");

        add(owner, tripId, existing)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
        add(owner, tripId, owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));

        assertThat(memberRows(tripId, existing)).isEqualTo(1);
    }

    @Test
    void unknownUserIsValidationFailedOnUserId() throws Exception {
        long owner = user("m-owner");
        long tripId = trip(owner);

        add(owner, tripId, 999_999_999L)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("userId"));
    }

    @Test
    void missingUserIdIsValidationFailed() throws Exception {
        long owner = user("m-owner");
        long tripId = trip(owner);

        add(owner, tripId, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("userId"));
    }

    // ---- 权限 ----

    @Test
    void plainMemberCannotAddAndOutsidersGetNotFound() throws Exception {
        long owner = user("m-owner");
        long plain = user("m-plain");
        long stranger = user("m-stranger");
        long leaver = user("m-leaver");
        long target = user("m-target");
        long tripId = trip(owner);
        member(tripId, plain, "member", "active");
        member(tripId, leaver, "member", "left");

        add(plain, tripId, target)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        add(stranger, tripId, target).andExpect(status().isNotFound());
        add(leaver, tripId, target).andExpect(status().isNotFound());
        add(owner, tripId + 1000, target).andExpect(status().isNotFound());

        assertThat(memberRows(tripId, target)).isZero();
    }

    @Test
    void deletedTripIsNotFound() throws Exception {
        long owner = user("m-owner");
        long target = user("m-target");
        long tripId = trip(owner);
        jdbc.update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", tripId);

        add(owner, tripId, target).andExpect(status().isNotFound());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/trips/1/members").contentType(MediaType.APPLICATION_JSON).content("{\"userId\": 1}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- 并发：不能靠"先查再插"，由唯一键裁决 ----

    @Test
    void concurrentAddOfSameNewUserCreatesOnceAndConflictsOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            long owner = user("m-owner");
            long target = user("m-target");
            long tripId = trip(owner);

            List<Integer> statuses = runConcurrently(2, () -> add(owner, tripId, target).andReturn().getResponse().getStatus());

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(memberRows(tripId, target)).isEqualTo(1);
        }
    }

    @Test
    void concurrentRejoinOfSameFormerMemberSucceedsOnceAndConflictsOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            long owner = user("m-owner");
            long returning = user("m-returning");
            long tripId = trip(owner);
            long oldMemberId = member(tripId, returning, "member", "left");

            List<Integer> statuses = runConcurrently(2, () -> add(owner, tripId, returning).andReturn().getResponse().getStatus());

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(memberRows(tripId, returning)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT id FROM trip_member WHERE trip_id = ? AND user_id = ?",
                    Long.class, tripId, returning)).isEqualTo(oldMemberId);
        }
    }

    @FunctionalInterface
    private interface Call {
        int run() throws Exception;
    }

    /** 让 count 个线程在同一时刻开始执行 call，返回各自的结果 */
    private List<Integer> runConcurrently(int count, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.run();
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

}
