package com.cshlands.api.member;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /trips/{tripId}/members 和 GET /trips/{tripId}/members/{memberId}。行程、成员直接用 JDBC 造。
 * MockMvc 不带 context-path，路径没有 /api。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class MemberQueryTest {

    /** 显式指定的大编号，用来构造"memberId 恰好等于另一个人的 userId" */
    private static final long EXPLICIT_ID = 900_000_003L;

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

    private ResultActions list(long actingUserId, long tripId, String query) throws Exception {
        return mockMvc.perform(get("/trips/" + tripId + "/members" + query).header("X-User-Id", actingUserId));
    }

    private ResultActions getMember(long actingUserId, long tripId, String memberId) throws Exception {
        return mockMvc.perform(get("/trips/" + tripId + "/members/" + memberId).header("X-User-Id", actingUserId));
    }

    // ---- 列表 ----

    @Test
    void listIncludesLeftMembersOrderedByMemberId() throws Exception {
        long owner = user("q-owner");
        long alice = user("q-alice");
        long bob = user("q-bob");
        long tripId = trip(owner);
        long aliceMemberId = member(tripId, alice, "member", "active");
        long bobMemberId = member(tripId, bob, "member", "left");

        list(alice, tripId, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].userId").value(owner))
                .andExpect(jsonPath("$.items[0].role").value("owner"))
                .andExpect(jsonPath("$.items[0].status").value("active"))
                .andExpect(jsonPath("$.items[0].nickname").value("q-owner"))
                .andExpect(jsonPath("$.items[1].memberId").value(aliceMemberId))
                .andExpect(jsonPath("$.items[1].role").value("member"))
                .andExpect(jsonPath("$.items[2].memberId").value(bobMemberId))
                .andExpect(jsonPath("$.items[2].status").value("left"))
                .andExpect(jsonPath("$.items[0].joinedAt").value(org.hamcrest.Matchers.endsWith("Z")));
    }

    @Test
    void statusFilterSelectsActiveOrLeft() throws Exception {
        long owner = user("q-owner");
        long alice = user("q-alice");
        long bob = user("q-bob");
        long tripId = trip(owner);
        member(tripId, alice, "member", "active");
        long bobMemberId = member(tripId, bob, "member", "left");

        list(owner, tripId, "?status=active")
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[?(@.status != 'active')]").isEmpty());
        list(owner, tripId, "?status=left")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].memberId").value(bobMemberId));
    }

    @Test
    void unknownStatusIsValidationFailed() throws Exception {
        long owner = user("q-owner");
        long tripId = trip(owner);

        // 注意：Spring 在自定义转换失败后还会按常量名再试一次，所以全大写的 ?status=ACTIVE 也会被放过，这里不测它
        for (String bad : new String[]{"?status=bogus", "?status=Active"}) {
            list(owner, tripId, bad)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fields[0].field").value("status"));
        }
    }

    @Test
    void listOnlyContainsThisTripsMembers() throws Exception {
        long owner = user("q-owner");
        long otherOwner = user("q-other");
        long tripId = trip(owner);
        trip(otherOwner);

        list(owner, tripId, "")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userId").value(owner));
    }

    @Test
    void listIsNotFoundForOutsidersLeftMembersAndDeletedTrips() throws Exception {
        long owner = user("q-owner");
        long stranger = user("q-stranger");
        long leaver = user("q-leaver");
        long tripId = trip(owner);
        member(tripId, leaver, "member", "left");

        list(stranger, tripId, "").andExpect(status().isNotFound());
        list(leaver, tripId, "").andExpect(status().isNotFound());
        list(owner, tripId + 1000, "").andExpect(status().isNotFound());

        jdbc.update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", tripId);
        list(owner, tripId, "").andExpect(status().isNotFound());
    }

    // ---- 单个成员 ----

    @Test
    void memberCanViewAnyMemberIncludingLeftOnes() throws Exception {
        long owner = user("q-owner");
        long alice = user("q-alice");
        long bob = user("q-bob");
        long tripId = trip(owner);
        long ownerMemberId = memberId(tripId, owner);
        member(tripId, alice, "member", "active");
        long bobMemberId = member(tripId, bob, "member", "left");

        getMember(alice, tripId, String.valueOf(ownerMemberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(ownerMemberId))
                .andExpect(jsonPath("$.userId").value(owner))
                .andExpect(jsonPath("$.nickname").value("q-owner"))
                .andExpect(jsonPath("$.role").value("owner"));
        getMember(alice, tripId, String.valueOf(bobMemberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("left"));
    }

    @Test
    void meMeansTheCallersOwnMember() throws Exception {
        long owner = user("q-owner");
        long alice = user("q-alice");
        long tripId = trip(owner);
        long aliceMemberId = member(tripId, alice, "member", "active");

        getMember(alice, tripId, "me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(aliceMemberId))
                .andExpect(jsonPath("$.userId").value(alice));
    }

    @Test
    void getUsesMemberIdNotUserId() throws Exception {
        long owner = user("q-owner");
        long target = user("q-target");
        long tripId = trip(owner);
        long bystander = userWithId(EXPLICIT_ID, "q-bystander");
        member(tripId, bystander, "member", "active");
        jdbc.update("INSERT INTO trip_member (id, trip_id, user_id, role, status) VALUES (?, ?, ?, 'member', 'active')",
                EXPLICIT_ID, tripId, target);

        getMember(owner, tripId, String.valueOf(EXPLICIT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(target))
                .andExpect(jsonPath("$.nickname").value("q-target"));
    }

    @Test
    void getRejectsForeignMissingAndMalformedMemberIds() throws Exception {
        long owner = user("q-owner");
        long otherOwner = user("q-other");
        long tripId = trip(owner);
        long otherTripId = trip(otherOwner);

        getMember(owner, tripId, String.valueOf(memberId(otherTripId, otherOwner)))
                .andExpect(status().isNotFound());
        getMember(owner, tripId, "999999999").andExpect(status().isNotFound());
        getMember(owner, tripId, "abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("memberId"));
    }

    @Test
    void getIsNotFoundForOutsidersAndLeftMembersAndMeOfNonMember() throws Exception {
        long owner = user("q-owner");
        long stranger = user("q-stranger");
        long leaver = user("q-leaver");
        long tripId = trip(owner);
        long ownerMemberId = memberId(tripId, owner);
        member(tripId, leaver, "member", "left");

        getMember(stranger, tripId, String.valueOf(ownerMemberId)).andExpect(status().isNotFound());
        getMember(leaver, tripId, String.valueOf(ownerMemberId)).andExpect(status().isNotFound());
        getMember(stranger, tripId, "me").andExpect(status().isNotFound());
        getMember(leaver, tripId, "me").andExpect(status().isNotFound());
    }

    @Test
    void requireAuthentication() throws Exception {
        mockMvc.perform(get("/trips/1/members")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/trips/1/members/2")).andExpect(status().isUnauthorized());
    }

}
