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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips/{tripId}/members/me/quit 和 POST /trips/{tripId}/members/{memberId}/remove。
 * 行程、成员直接用 JDBC 造。MockMvc 不带 context-path，路径没有 /api。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class MemberLeaveTest {

    /** 显式指定的大编号，保证不会和自增的 id 撞上，用来构造"memberId 恰好等于另一个人的 userId" */
    private static final long EXPLICIT_ID = 900_000_001L;

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

    private String statusOf(long tripId, long userId) {
        return jdbc.queryForObject("SELECT status FROM trip_member WHERE trip_id = ? AND user_id = ?",
                String.class, tripId, userId);
    }

    private ResultActions quit(long actingUserId, long tripId) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/members/me/quit").header("X-User-Id", actingUserId));
    }

    private ResultActions remove(long actingUserId, long tripId, long memberId) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/members/" + memberId + "/remove")
                .header("X-User-Id", actingUserId));
    }

    private ResultActions getTrip(long userId, long tripId) throws Exception {
        return mockMvc.perform(get("/trips/" + tripId).header("X-User-Id", userId));
    }

    // ---- 退出 ----

    @Test
    void memberQuitsAndLosesAccessUntilReAdded() throws Exception {
        long owner = user("l-owner");
        long member = user("l-member");
        long tripId = trip(owner);
        long memberId = member(tripId, member, "member", "active");

        quit(member, tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.memberId").value(memberId))
                .andExpect(jsonPath("$.member.userId").value(member))
                .andExpect(jsonPath("$.member.status").value("left"))
                .andExpect(jsonPath("$.warnings", hasSize(0)));
        assertThat(statusOf(tripId, member)).isEqualTo("left");

        // 退出后这个 Trip 下的接口对他都是 404；重新加入后恢复，memberId 不变
        getTrip(member, tripId).andExpect(status().isNotFound());
        mockMvc.perform(post("/trips/" + tripId + "/members").header("X-User-Id", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\": " + member + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(memberId));
        getTrip(member, tripId).andExpect(status().isOk());
    }

    @Test
    void ownerCannotQuitAndStaysOwner() throws Exception {
        long owner = user("l-owner");
        long tripId = trip(owner);

        quit(owner, tripId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_MUST_TRANSFER_FIRST"));

        assertThat(statusOf(tripId, owner)).isEqualTo("active");
        assertThat(jdbc.queryForObject("SELECT role FROM trip_member WHERE trip_id = ? AND user_id = ?",
                String.class, tripId, owner)).isEqualTo("owner");
    }

    @Test
    void nonMemberAndAlreadyLeftAreNotFound() throws Exception {
        long owner = user("l-owner");
        long stranger = user("l-stranger");
        long leaver = user("l-leaver");
        long tripId = trip(owner);
        member(tripId, leaver, "member", "left");

        quit(stranger, tripId).andExpect(status().isNotFound());
        quit(leaver, tripId).andExpect(status().isNotFound());
        quit(owner, tripId + 1000).andExpect(status().isNotFound());
    }

    @Test
    void quitRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/trips/1/members/me/quit")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentQuitOfSameMemberSucceedsOnceAndThenNotFound() throws Exception {
        for (int round = 0; round < 5; round++) {
            long owner = user("l-owner");
            long member = user("l-member");
            long tripId = trip(owner);
            member(tripId, member, "member", "active");

            List<Integer> statuses = runConcurrently(2, () -> quit(member, tripId).andReturn().getResponse().getStatus());

            assertThat(statuses).containsExactlyInAnyOrder(200, 404);
            assertThat(statusOf(tripId, member)).isEqualTo("left");
        }
    }

    // ---- 踢人 ----

    @Test
    void removeUsesMemberIdNotUserId() throws Exception {
        long owner = user("l-owner");
        long target = user("l-target");
        long tripId = trip(owner);

        // 构造陷阱：目标的 memberId 恰好等于另一个在籍成员的 userId。
        // 如果实现把 memberId 当 userId 用，被踢的会是 bystander。
        long bystander = userWithId(EXPLICIT_ID, "l-bystander");
        member(tripId, bystander, "member", "active");
        jdbc.update("INSERT INTO trip_member (id, trip_id, user_id, role, status) VALUES (?, ?, ?, 'member', 'active')",
                EXPLICIT_ID, tripId, target);
        assertThat(memberId(tripId, target)).isEqualTo(bystander);
        assertThat(memberId(tripId, bystander)).isNotEqualTo(bystander);

        remove(owner, tripId, EXPLICIT_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.memberId").value(EXPLICIT_ID))
                .andExpect(jsonPath("$.member.userId").value(target))
                .andExpect(jsonPath("$.member.status").value("left"))
                .andExpect(jsonPath("$.warnings", hasSize(0)));

        assertThat(statusOf(tripId, target)).isEqualTo("left");
        assertThat(statusOf(tripId, bystander)).isEqualTo("active");
        getTrip(target, tripId).andExpect(status().isNotFound());
    }

    @Test
    void removingAlreadyLeftMemberIsOkAndRepeatable() throws Exception {
        long owner = user("l-owner");
        long gone = user("l-gone");
        long tripId = trip(owner);
        long goneMemberId = member(tripId, gone, "member", "left");

        for (int i = 0; i < 2; i++) {
            remove(owner, tripId, goneMemberId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.member.status").value("left"));
        }
    }

    @Test
    void ownerCannotRemoveSelf() throws Exception {
        long owner = user("l-owner");
        long tripId = trip(owner);

        remove(owner, tripId, memberId(tripId, owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_REMOVE_OWNER"));
        assertThat(statusOf(tripId, owner)).isEqualTo("active");
    }

    @Test
    void memberIdOfAnotherTripOrMissingIsNotFound() throws Exception {
        long owner = user("l-owner");
        long otherOwner = user("l-other-owner");
        long tripId = trip(owner);
        long otherTripId = trip(otherOwner);
        long foreignMemberId = memberId(otherTripId, otherOwner);

        remove(owner, tripId, foreignMemberId).andExpect(status().isNotFound());
        remove(owner, tripId, 999_999_999L).andExpect(status().isNotFound());
        assertThat(statusOf(otherTripId, otherOwner)).isEqualTo("active");
    }

    @Test
    void plainMemberCannotRemoveAndOutsidersGetNotFound() throws Exception {
        long owner = user("l-owner");
        long plain = user("l-plain");
        long victim = user("l-victim");
        long stranger = user("l-stranger");
        long tripId = trip(owner);
        member(tripId, plain, "member", "active");
        long victimMemberId = member(tripId, victim, "member", "active");

        remove(plain, tripId, victimMemberId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        remove(stranger, tripId, victimMemberId).andExpect(status().isNotFound());
        assertThat(statusOf(tripId, victim)).isEqualTo("active");
    }

    @Test
    void removeRequiresAuthenticationAndRejectsNonNumericMemberId() throws Exception {
        mockMvc.perform(post("/trips/1/members/2/remove")).andExpect(status().isUnauthorized());

        long owner = user("l-owner");
        long tripId = trip(owner);
        mockMvc.perform(post("/trips/" + tripId + "/members/abc/remove").header("X-User-Id", owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("memberId"));
    }

    // ---- 并发 ----

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
