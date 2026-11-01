package com.cshlands.api.debt;

import com.cshlands.api.MySqlTestContainerConfig;
import com.cshlands.api.repayment.RepaymentTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /trips/{tripId}/debts/{memberId}：当前用户和这个成员之间的一对一净额，按币别分列。
 * 净额的算法和总览一样（见 DebtOverviewTest），这里只测"只看这一对""谁是当前用户"和边界。
 * 成员 memberId 升序：me < alice < bob < carol。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class DebtPairTest extends RepaymentTestBase {

    // ---- 基本 ----

    @Test
    void noDebtBetweenThemIsAnEmptyArray() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.aliceMember, "JPY", s.bobMember, 100_000);   // 别人之间的，和 me、alice 无关

        debtsWith(s.meUser, s.tripId, s.aliceMember)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void responseHasExactlyTheContractFields() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);

        String json = debtsWith(s.meUser, s.tripId, s.aliceMember).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys("items");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.items[0]"))
                .containsOnlyKeys("fromMemberId", "toMemberId", "currency", "amountMinor", "pendingMinor");
    }

    @Test
    void showsTheDebtInBothDirectionsPerCurrency() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);    // alice 欠 me（JPY）
        expense(s.tripId, s.aliceMember, "HKD", s.meMember, 5_000);      // me 欠 alice（HKD）

        assertThat(pairOverview(s.meUser, s.tripId, s.aliceMember)).containsExactly(
                owes(s.meMember, s.aliceMember, "HKD", 5_000),
                owes(s.aliceMember, s.meMember, "JPY", 100_000));
    }

    @Test
    void onlyTheDebtsBetweenThePairAreShown() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);    // me—alice
        expense(s.tripId, s.meMember, "JPY", s.bobMember, 200_000);      // me—bob
        expense(s.tripId, s.aliceMember, "JPY", s.bobMember, 300_000);   // alice—bob

        assertThat(pairOverview(s.meUser, s.tripId, s.aliceMember)).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 100_000));
        assertThat(pairOverview(s.meUser, s.tripId, s.bobMember)).containsExactly(owes(s.bobMember, s.meMember, "JPY", 200_000));
    }

    @Test
    void severalExpensesAreNettedPerCurrency() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 150_000);
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        expense(s.tripId, s.aliceMember, "JPY", s.meMember, 50_000);
        expense(s.tripId, s.meMember, "HKD", s.aliceMember, 2_000);
        expense(s.tripId, s.aliceMember, "HKD", s.meMember, 2_000);       // HKD 正好抵消

        assertThat(pairOverview(s.meUser, s.tripId, s.aliceMember)).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 200_000));
    }

    @Test
    void personalAndDeletedExpensesAreNotCounted() throws Exception {
        Scene s = new Scene();
        personalExpense(s.tripId, s.aliceMember, "JPY", 80_000);
        long gone = expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        softDeleteExpense(gone);

        assertThat(pairOverview(s.meUser, s.tripId, s.aliceMember)).isEmpty();
    }

    @Test
    void debtsOfAnotherTripAreNotMixedIn() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();
        expense(other.tripId, other.meMember, "JPY", other.aliceMember, 100_000);

        assertThat(pairOverview(s.meUser, s.tripId, s.aliceMember)).isEmpty();
    }

    // ---- 还款 ----

    @Test
    void confirmedRepaymentsReduceItAndPendingOnesAreListedAsPending() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        confirmed(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 50_000);
        cancelled(s.tripId, s.aliceMember, s.meMember, "JPY", 70_000);

        assertThat(pairOverview(s.meUser, s.tripId, s.aliceMember))
                .containsExactly(owes(s.aliceMember, s.meMember, "JPY", 200_000, 50_000));
    }

    @Test
    void anOverpaidDebtFlipsDirection() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        confirmed(s.tripId, s.aliceMember, s.meMember, "JPY", 150_000);

        assertThat(pairOverview(s.meUser, s.tripId, s.aliceMember)).containsExactly(owes(s.meMember, s.aliceMember, "JPY", 50_000));
    }

    @Test
    void aRepaymentWithAThirdPersonDoesNotCountForThePair() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        confirmed(s.tripId, s.aliceMember, s.bobMember, "JPY", 100_000);
        pending(s.tripId, s.bobMember, s.meMember, "JPY", 100_000);

        assertThat(pairOverview(s.meUser, s.tripId, s.aliceMember)).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 100_000));
    }

    // ---- 谁是"当前用户" ----

    @Test
    void bothPeopleSeeTheSamePair() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);

        assertThat(pairOverview(s.aliceUser, s.tripId, s.meMember)).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 100_000));
        assertThat(pairOverview(s.meUser, s.tripId, s.aliceMember)).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 100_000));
    }

    @Test
    void theOtherPartyIsPairedWithTheCurrentUserNotWithSomeoneElse() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);

        // carol 看和 me 的这一对，里面没有 alice 欠 me 的那笔
        assertThat(pairOverview(s.carolUser, s.tripId, s.meMember)).isEmpty();
    }

    @Test
    void theMemberIdInThePathIsAMemberIdNotAUserId() throws Exception {
        long ownerUser = user("r-owner");
        long tripId = trip(ownerUser);
        long owner = memberId(tripId, ownerUser);
        // X 的 userId 恰好等于 Y 的 memberId
        long userX = userWithId(EXPLICIT_ID, "r-x");
        long memberX = member(tripId, userX, "member", "active");
        long userY = user("r-y");
        long memberY = memberWithId(EXPLICIT_ID, tripId, userY, "member", "active");
        expense(tripId, owner, "JPY", memberX, 100_000);
        expense(tripId, owner, "JPY", memberY, 200_000);

        assertThat(pairOverview(ownerUser, tripId, EXPLICIT_ID)).containsExactly(owes(memberY, owner, "JPY", 200_000));
    }

    // ---- 边界 ----

    @Test
    void theOtherPartyMayHaveLeft() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.bobMember, 100_000);
        leave(s.bobMember);

        assertThat(pairOverview(s.meUser, s.tripId, s.bobMember)).containsExactly(owes(s.bobMember, s.meMember, "JPY", 100_000));
    }

    @Test
    void askingAboutYourselfIs400() throws Exception {
        Scene s = new Scene();

        debtsWith(s.meUser, s.tripId, s.meMember)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[*].field", hasItem("memberId")));
    }

    @Test
    void aMemberOutsideTheTripIs404() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();

        debtsWith(s.meUser, s.tripId, other.aliceMember)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        debtsWith(s.meUser, s.tripId, 999_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aMemberIdThatIsNotANumberIs400() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(get("/trips/" + s.tripId + "/debts/abc").header("X-User-Id", s.meUser))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- 访问 ----

    @Test
    void aNonMemberGets404() throws Exception {
        Scene s = new Scene();
        long outsider = user("r-outsider");

        debtsWith(outsider, s.tripId, s.aliceMember)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aMemberWhoLeftGets404() throws Exception {
        Scene s = new Scene();
        leave(s.meMember);

        debtsWith(s.meUser, s.tripId, s.aliceMember)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aDeletedTripGets404() throws Exception {
        Scene s = new Scene();
        deleteTrip(s.tripId);

        debtsWith(s.meUser, s.tripId, s.aliceMember)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void withoutIdentityIs401() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(get("/trips/" + s.tripId + "/debts/" + s.aliceMember))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
