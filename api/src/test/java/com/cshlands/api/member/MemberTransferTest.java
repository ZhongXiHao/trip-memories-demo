package com.cshlands.api.member;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips/{tripId}/owner/transfer/{memberId}。行程、成员直接用 JDBC 造。
 * MockMvc 不带 context-path，路径没有 /api。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class MemberTransferTest {

    /** 显式指定的大编号，用来构造"memberId 恰好等于另一个人的 userId" */
    private static final long EXPLICIT_ID = 900_000_002L;

    private static final String TRIP_JSON = """
            {"name": "改名了", "displayCurrency": "HKD", "timezone": "UTC"}""";

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

    private long userWithId(long id, String nickname) {
        jdbc.update("INSERT INTO app_user (id, nickname) VALUES (?, ?)", id, nickname);
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
        return memberId(tripId, userId);
    }

    private long memberId(long tripId, long userId) {
        return jdbc.queryForObject("SELECT id FROM trip_member WHERE trip_id = ? AND user_id = ?",
                Long.class, tripId, userId);
    }

    private String roleOf(long tripId, long userId) {
        return jdbc.queryForObject("SELECT role FROM trip_member WHERE trip_id = ? AND user_id = ?",
                String.class, tripId, userId);
    }

    private int ownerCount(long tripId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trip_member WHERE trip_id = ? AND role = 'owner'",
                Integer.class, tripId);
    }

    private ResultActions transfer(long actingUserId, long tripId, long memberId) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/owner/transfer/" + memberId)
                .header("X-User-Id", actingUserId));
    }

    // ---- 成功路径 ----

    @Test
    void ownerTransfersToActiveMemberAndRolesSwap() throws Exception {
        long oldOwner = user("o-old");
        long newOwner = user("o-new");
        long tripId = trip(oldOwner);
        long oldOwnerMemberId = memberId(tripId, oldOwner);
        long newOwnerMemberId = member(tripId, newOwner, "member", "active");

        transfer(oldOwner, tripId, newOwnerMemberId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousOwner.memberId").value(oldOwnerMemberId))
                .andExpect(jsonPath("$.previousOwner.role").value("member"))
                .andExpect(jsonPath("$.previousOwner.status").value("active"))
                .andExpect(jsonPath("$.newOwner.memberId").value(newOwnerMemberId))
                .andExpect(jsonPath("$.newOwner.role").value("owner"));

        assertThat(roleOf(tripId, oldOwner)).isEqualTo("member");
        assertThat(roleOf(tripId, newOwner)).isEqualTo("owner");
        assertThat(ownerCount(tripId)).isEqualTo(1);
    }

    @Test
    void permissionsFollowTheNewOwner() throws Exception {
        long oldOwner = user("o-old");
        long newOwner = user("o-new");
        long tripId = trip(oldOwner);
        long newOwnerMemberId = member(tripId, newOwner, "member", "active");

        transfer(oldOwner, tripId, newOwnerMemberId).andExpect(status().isOk());

        // 旧 Owner 现在只是普通成员：不能改 Trip，也不能再转让；但现在可以自己退出了
        mockMvc.perform(put("/trips/" + tripId).header("X-User-Id", oldOwner)
                        .contentType(MediaType.APPLICATION_JSON).content(TRIP_JSON))
                .andExpect(status().isForbidden());
        transfer(oldOwner, tripId, newOwnerMemberId).andExpect(status().isForbidden());
        // 新 Owner 可以改 Trip，但退出要先转让
        mockMvc.perform(put("/trips/" + tripId).header("X-User-Id", newOwner)
                        .contentType(MediaType.APPLICATION_JSON).content(TRIP_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(post("/trips/" + tripId + "/members/me/quit").header("X-User-Id", newOwner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_MUST_TRANSFER_FIRST"));
        mockMvc.perform(post("/trips/" + tripId + "/members/me/quit").header("X-User-Id", oldOwner))
                .andExpect(status().isOk());
    }

    @Test
    void transferUsesMemberIdNotUserId() throws Exception {
        long owner = user("o-owner");
        long target = user("o-target");
        long tripId = trip(owner);

        // 目标的 memberId 恰好等于 bystander 的 userId；把 memberId 当 userId 会升错人
        long bystander = userWithId(EXPLICIT_ID, "o-bystander");
        member(tripId, bystander, "member", "active");
        jdbc.update("INSERT INTO trip_member (id, trip_id, user_id, role, status) VALUES (?, ?, ?, 'member', 'active')",
                EXPLICIT_ID, tripId, target);

        transfer(owner, tripId, EXPLICIT_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newOwner.userId").value(target));

        assertThat(roleOf(tripId, target)).isEqualTo("owner");
        assertThat(roleOf(tripId, bystander)).isEqualTo("member");
        assertThat(ownerCount(tripId)).isEqualTo(1);
    }

    // ---- 拒绝的情况，并且角色都不能变 ----

    @Test
    void transferToSelfIsValidationFailed() throws Exception {
        long owner = user("o-owner");
        long tripId = trip(owner);

        transfer(owner, tripId, memberId(tripId, owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("memberId"));
        assertThat(roleOf(tripId, owner)).isEqualTo("owner");
    }

    @Test
    void transferToLeftMemberIsMemberLeftAndOwnerStays() throws Exception {
        long owner = user("o-owner");
        long gone = user("o-gone");
        long tripId = trip(owner);
        long goneMemberId = member(tripId, gone, "member", "left");

        transfer(owner, tripId, goneMemberId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_LEFT"));

        assertThat(roleOf(tripId, owner)).isEqualTo("owner");
        assertThat(roleOf(tripId, gone)).isEqualTo("member");
    }

    @Test
    void memberIdOfAnotherTripOrMissingIsNotFound() throws Exception {
        long owner = user("o-owner");
        long otherOwner = user("o-other");
        long tripId = trip(owner);
        long otherTripId = trip(otherOwner);

        transfer(owner, tripId, memberId(otherTripId, otherOwner)).andExpect(status().isNotFound());
        transfer(owner, tripId, 999_999_999L).andExpect(status().isNotFound());
        assertThat(roleOf(tripId, owner)).isEqualTo("owner");
        assertThat(roleOf(otherTripId, otherOwner)).isEqualTo("owner");
    }

    @Test
    void plainMemberCannotTransferAndOutsidersGetNotFound() throws Exception {
        long owner = user("o-owner");
        long plain = user("o-plain");
        long other = user("o-other");
        long stranger = user("o-stranger");
        long tripId = trip(owner);
        long plainMemberId = member(tripId, plain, "member", "active");
        long otherMemberId = member(tripId, other, "member", "active");

        transfer(plain, tripId, otherMemberId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        transfer(plain, tripId, plainMemberId).andExpect(status().isForbidden());
        transfer(stranger, tripId, otherMemberId).andExpect(status().isNotFound());

        assertThat(roleOf(tripId, owner)).isEqualTo("owner");
        assertThat(ownerCount(tripId)).isEqualTo(1);
    }

    @Test
    void requiresAuthenticationAndNumericMemberId() throws Exception {
        mockMvc.perform(post("/trips/1/owner/transfer/2")).andExpect(status().isUnauthorized());

        long owner = user("o-owner");
        long tripId = trip(owner);
        mockMvc.perform(post("/trips/" + tripId + "/owner/transfer/abc").header("X-User-Id", owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("memberId"));
    }

    // ---- 并发 ----

    @Test
    void concurrentTransfersToDifferentTargetsLeaveExactlyOneOwner() throws Exception {
        for (int round = 0; round < 5; round++) {
            long owner = user("o-owner");
            long a = user("o-a");
            long b = user("o-b");
            long tripId = trip(owner);
            long aMemberId = member(tripId, a, "member", "active");
            long bMemberId = member(tripId, b, "member", "active");

            List<Integer> statuses = runConcurrently(
                    () -> transfer(owner, tripId, aMemberId).andReturn().getResponse().getStatus(),
                    () -> transfer(owner, tripId, bMemberId).andReturn().getResponse().getStatus());

            // 先到的成功；后到的发起人已经不是 Owner，被拒绝（403，或者在鉴权时就被 403 挡住）
            assertThat(statuses).containsExactlyInAnyOrder(200, 403);
            assertThat(ownerCount(tripId)).isEqualTo(1);
            assertThat(roleOf(tripId, owner)).isEqualTo("member");
        }
    }

    @FunctionalInterface
    private interface Call {
        int run() throws Exception;
    }

    /** 让所有 call 在同一时刻开始执行，返回各自的结果 */
    private List<Integer> runConcurrently(Call... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch ready = new CountDownLatch(calls.length);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Call call : calls) {
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
