package com.cshlands.api.repayment;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /trips/{tripId}/repayments?status=&memberId=&settlementGroupId=：还款列表。
 * 在籍成员都能看到行程里的全部还款（没取消的），不限于自己是付款方或收款方；
 * memberId 筛选的意思是"付款方或收款方是他"。排序：createdAt 倒序，相同的按 id 倒序。
 * 成员 memberId 升序：me < alice < bob < carol。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class RepaymentListTest extends RepaymentTestBase {

    private List<Long> ids(long userId, Scene s, String query) throws Exception {
        String json = listRepayments(userId, s.tripId, query).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return idsIn(json);
    }

    // ---- 基本 ----

    @Test
    void noRepaymentsIsAnEmptyArray() throws Exception {
        Scene s = new Scene();

        listRepayments(s.meUser, s.tripId, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void theItemsHaveExactlyTheContractFields() throws Exception {
        Scene s = new Scene();
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        String json = listRepayments(s.meUser, s.tripId, "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys("items");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.items[0]")).containsOnlyKeys(
                "id", "settlementGroupId", "expenseId", "fromMemberId", "toMemberId",
                "clearedAmountMinor", "clearedCurrency", "paidAmountMinor", "paidCurrency",
                "status", "confirmedAt", "note", "createdByMemberId", "version", "createdAt");
    }

    @Test
    void aListedItemEqualsTheObjectReturnedWhenItWasCreated() throws Exception {
        // 端到端：依赖创建还款接口
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        RepaymentBody body = RepaymentBody.of(uuid(), s.aliceMember, s.meMember, "JPY", item("JPY", 100_000))
                .str("note", "先还一部分");
        String created = createRepayment(s.aliceUser, s.tripId, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String listed = listRepayments(s.carolUser, s.tripId, "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Object>read(listed, "$.items[0]")).isEqualTo(JsonPath.<Object>read(created, "$.items[0]"));
    }

    @Test
    void everyActiveMemberSeesEveryRepaymentNotJustTheirOwn() throws Exception {
        Scene s = new Scene();
        long a = pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
        long b = confirmed(s.tripId, s.bobMember, s.meMember, "JPY", 50_000);

        for (long user : new long[]{s.meUser, s.aliceUser, s.bobUser, s.carolUser}) {
            assertThat(ids(user, s, "")).containsExactlyInAnyOrder(a, b);
        }
    }

    @Test
    void cancelledRepaymentsAreNotListedEvenInsideAGroupThatStillHasOthers() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        long kept = groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "pending", true);
        cancelled(s.tripId, s.bobMember, s.meMember, "JPY", 100_000);

        assertThat(ids(s.meUser, s, "")).containsExactly(kept);
    }

    @Test
    void repaymentsOfMembersWhoLeftAreStillListed() throws Exception {
        Scene s = new Scene();
        long id = pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
        leave(s.aliceMember);

        assertThat(ids(s.meUser, s, "")).containsExactly(id);
    }

    @Test
    void aGroupShowsAllItsRowsWithTheSameSettlementGroupId() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "pending", false);

        String json = listRepayments(s.meUser, s.tripId, "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(json, "$.items[*].settlementGroupId")).containsExactly(g, g);
    }

    @Test
    void repaymentsOfAnotherTripAreNotListed() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();
        long mine = pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
        pending(other.tripId, other.aliceMember, other.meMember, "JPY", 100_000);

        assertThat(ids(s.meUser, s, "")).containsExactly(mine);
    }

    @Test
    void orderIsCreatedAtDescendingThenIdDescending() throws Exception {
        Scene s = new Scene();
        long oldest = pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
        long sameTimeFirst = pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
        long sameTimeLater = pending(s.tripId, s.bobMember, s.meMember, "JPY", 100_000);
        long newest = pending(s.tripId, s.carolMember, s.meMember, "JPY", 100_000);
        setCreatedAt(oldest, "2026-10-10 09:00:00.000");
        setCreatedAt(sameTimeFirst, "2026-10-11 09:00:00.000");
        setCreatedAt(sameTimeLater, "2026-10-11 09:00:00.000");
        setCreatedAt(newest, "2026-10-12 09:00:00.000");

        assertThat(ids(s.meUser, s, "")).containsExactly(newest, sameTimeLater, sameTimeFirst, oldest);
    }

    // ---- status ----

    @Test
    void statusKeepsOnlyThatStatus() throws Exception {
        Scene s = new Scene();
        long p = pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
        long c = confirmed(s.tripId, s.bobMember, s.meMember, "JPY", 100_000);

        assertThat(ids(s.meUser, s, "status=pending")).containsExactly(p);
        assertThat(ids(s.meUser, s, "status=confirmed")).containsExactly(c);
    }

    @Test
    void statusMustBePendingOrConfirmed() throws Exception {
        Scene s = new Scene();
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        // 已取消的不会出现在列表里，所以 cancelled 也不是合法的筛选值
        List<String> failures = new ArrayList<>();
        for (String bad : List.of("status=cancelled", "status=done")) {
            var result = listRepayments(s.meUser, s.tripId, bad).andReturn();
            String body = result.getResponse().getContentAsString();
            boolean ok = result.getResponse().getStatus() == 400
                    && body.contains("\"VALIDATION_FAILED\"")
                    && JsonPath.<List<String>>read(body, "$.fields[*].field").contains("status");
            if (!ok) {
                failures.add(bad + " → " + result.getResponse().getStatus() + " " + body);
            }
        }
        assertThat(failures).isEmpty();
    }

    // ---- memberId ----

    @Test
    void memberIdKeepsTheRepaymentsWhereThatMemberIsPayerOrPayee() throws Exception {
        Scene s = new Scene();
        long alicePays = pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
        long aliceReceives = confirmed(s.tripId, s.bobMember, s.aliceMember, "JPY", 100_000);
        pending(s.tripId, s.bobMember, s.meMember, "JPY", 100_000);    // 和 alice 无关
        pending(s.tripId, s.carolMember, s.bobMember, "JPY", 100_000); // 和 alice 无关

        assertThat(ids(s.carolUser, s, "memberId=" + s.aliceMember)).containsExactlyInAnyOrder(alicePays, aliceReceives);
    }

    @Test
    void memberIdMeansMemberIdNotUserId() throws Exception {
        long ownerUser = user("r-owner");
        long tripId = trip(ownerUser);
        long owner = memberId(tripId, ownerUser);
        // X 的 userId 恰好等于 Y 的 memberId
        long userX = userWithId(EXPLICIT_ID, "r-x");
        long memberX = member(tripId, userX, "member", "active");
        long userY = user("r-y");
        long memberY = memberWithId(EXPLICIT_ID, tripId, userY, "member", "active");
        pending(tripId, memberX, owner, "JPY", 100_000);
        long ys = pending(tripId, memberY, owner, "JPY", 100_000);

        String json = listRepayments(ownerUser, tripId, "memberId=" + EXPLICIT_ID).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(idsIn(json)).containsExactly(ys);
    }

    @Test
    void memberIdCanBeAMemberWhoLeft() throws Exception {
        Scene s = new Scene();
        long id = pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
        pending(s.tripId, s.bobMember, s.meMember, "JPY", 100_000);
        leave(s.aliceMember);

        assertThat(ids(s.meUser, s, "memberId=" + s.aliceMember)).containsExactly(id);
    }

    @Test
    void aMemberIdOutsideTheTripGivesAnEmptyArray() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        assertThat(ids(s.meUser, s, "memberId=" + other.aliceMember)).isEmpty();
        assertThat(ids(s.meUser, s, "memberId=999999999")).isEmpty();
    }

    @Test
    void aMemberIdThatIsNotANumberIs400() throws Exception {
        Scene s = new Scene();

        listRepayments(s.meUser, s.tripId, "memberId=abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- settlementGroupId ----

    @Test
    void settlementGroupIdKeepsOnlyThatGroup() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        long jpy = groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        long hkd = groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "pending", false);
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);   // 别的组

        assertThat(ids(s.carolUser, s, "settlementGroupId=" + g)).containsExactlyInAnyOrder(jpy, hkd);
        assertThat(ids(s.carolUser, s, "settlementGroupId=" + uuid())).isEmpty();
    }

    @Test
    void settlementGroupIdMustBeAUuid() throws Exception {
        Scene s = new Scene();

        listRepayments(s.meUser, s.tripId, "settlementGroupId=not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[*].field", hasItem("settlementGroupId")));
    }

    @Test
    void severalFiltersGiveTheIntersection() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        long match = groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        groupRow(s.tripId, g, s.bobMember, s.meMember, "HKD", 2_000, "confirmed", false);   // 同组但是 bob 的、已确认
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);                       // alice 的但是别的组

        assertThat(ids(s.meUser, s, "settlementGroupId=" + g + "&memberId=" + s.aliceMember + "&status=pending"))
                .containsExactly(match);
        assertThat(ids(s.meUser, s, "settlementGroupId=" + g + "&memberId=" + s.aliceMember + "&status=confirmed"))
                .isEmpty();
    }

    // ---- 访问 ----

    @Test
    void aNonMemberGets404() throws Exception {
        Scene s = new Scene();
        long outsider = user("r-outsider");

        listRepayments(outsider, s.tripId, "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aMemberWhoLeftGets404() throws Exception {
        Scene s = new Scene();
        leave(s.aliceMember);

        listRepayments(s.aliceUser, s.tripId, "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aDeletedTripGets404() throws Exception {
        Scene s = new Scene();
        deleteTrip(s.tripId);

        listRepayments(s.meUser, s.tripId, "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void withoutIdentityIs401() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(get("/trips/" + s.tripId + "/repayments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
