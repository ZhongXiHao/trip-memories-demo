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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /trips/{tripId}/debts?memberId=：欠款总览（不含 simplify，那个在 DebtSimplifyTest）。
 * 金额都是"最小单位 × 100"：100_000 = JPY 1000。数据用 JDBC 直接造，包括绕过额度限制的还款。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class DebtOverviewTest extends RepaymentTestBase {

    // ---- 基本 ----

    @Test
    void noExpensesIsAnEmptyArray() throws Exception {
        Scene s = new Scene();

        debts(s.meUser, s.tripId, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void responseHasExactlyTheContractFields() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);

        String json = debts(s.meUser, s.tripId, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys("items");
        Map<String, Object> item = JsonPath.read(json, "$.items[0]");
        assertThat(item).containsOnlyKeys("fromMemberId", "toMemberId", "currency", "amountMinor", "pendingMinor");
        assertThat(item.get("currency")).isEqualTo("JPY");
    }

    @Test
    void aSharedExpenseMakesEveryOtherSharerOweThePayer() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.aliceMember, "JPY", s.meMember, 100_000, s.aliceMember, 100_000, s.bobMember, 100_000);

        // alice 付款，自己的那一份不是欠款
        assertThat(overview(s.carolUser, s.tripId, "")).containsExactly(
                owes(s.meMember, s.aliceMember, "JPY", 100_000),
                owes(s.bobMember, s.aliceMember, "JPY", 100_000));
    }

    @Test
    void severalExpensesInTheSameDirectionAddUp() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 250_000, s.meMember, 50_000);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 350_000));
    }

    @Test
    void itemsAreOrderedByFromThenToThenCurrency() throws Exception {
        Scene s = new Scene();
        // 故意打乱造的顺序
        expense(s.tripId, s.carolMember, "JPY", s.aliceMember, 400_000, s.bobMember, 200_000);
        expense(s.tripId, s.aliceMember, "JPY", s.meMember, 300_000);
        expense(s.tripId, s.meMember, "HKD", s.bobMember, 100_000);
        expense(s.tripId, s.aliceMember, "HKD", s.meMember, 500_000);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(
                owes(s.meMember, s.aliceMember, "HKD", 500_000),
                owes(s.meMember, s.aliceMember, "JPY", 300_000),
                owes(s.aliceMember, s.carolMember, "JPY", 400_000),
                owes(s.bobMember, s.meMember, "HKD", 100_000),
                owes(s.bobMember, s.carolMember, "JPY", 200_000));
    }

    @Test
    void aPersonalExpenseCreatesNoDebt() throws Exception {
        Scene s = new Scene();
        personalExpense(s.tripId, s.aliceMember, "JPY", 80_000);

        assertThat(overview(s.meUser, s.tripId, "")).isEmpty();
    }

    @Test
    void aDeletedExpenseCreatesNoDebt() throws Exception {
        Scene s = new Scene();
        long gone = expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        expense(s.tripId, s.meMember, "JPY", s.bobMember, 70_000);
        softDeleteExpense(gone);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.bobMember, s.meMember, "JPY", 70_000));
    }

    @Test
    void debtsOfAnotherTripAreNotMixedIn() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();
        expense(other.tripId, other.meMember, "JPY", other.aliceMember, 100_000);
        expense(s.tripId, s.meMember, "JPY", s.bobMember, 20_000);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.bobMember, s.meMember, "JPY", 20_000));
        assertThat(overview(other.meUser, other.tripId, "")).containsExactly(owes(other.aliceMember, other.meMember, "JPY", 100_000));
    }

    @Test
    void aMemberWhoLeftStillOwesAndIsStillOwed() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.aliceMember, "JPY", s.bobMember, 100_000);
        expense(s.tripId, s.bobMember, "JPY", s.carolMember, 50_000);
        leave(s.bobMember);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(
                owes(s.bobMember, s.aliceMember, "JPY", 100_000),
                owes(s.carolMember, s.bobMember, "JPY", 50_000));
    }

    // ---- 净额：同币别内双向轧差 ----

    @Test
    void debtsInOppositeDirectionsAreNetted() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 150_000, s.meMember, 150_000); // alice 欠 me 150_000
        expense(s.tripId, s.aliceMember, "JPY", s.meMember, 50_000);                       // me 欠 alice 50_000

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 100_000));
    }

    @Test
    void theNetDirectionFollowsTheLargerSide() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 50_000);
        expense(s.tripId, s.aliceMember, "JPY", s.meMember, 150_000);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.meMember, s.aliceMember, "JPY", 100_000));
    }

    @Test
    void equalDebtsInOppositeDirectionsCancelOut() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 80_000);
        expense(s.tripId, s.aliceMember, "JPY", s.meMember, 80_000);

        assertThat(overview(s.meUser, s.tripId, "")).isEmpty();
    }

    @Test
    void currenciesNeverOffsetEachOther() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);  // alice 欠 me（JPY）
        expense(s.tripId, s.aliceMember, "HKD", s.meMember, 5_000);    // me 欠 alice（HKD）

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(
                owes(s.meMember, s.aliceMember, "HKD", 5_000),
                owes(s.aliceMember, s.meMember, "JPY", 100_000));
    }

    @Test
    void debtsAreNotMergedAcrossPeopleByDefault() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);   // alice 欠 bob
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 100_000);   // bob 欠 carol

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(
                owes(s.aliceMember, s.bobMember, "JPY", 100_000),
                owes(s.bobMember, s.carolMember, "JPY", 100_000));
    }

    // ---- 已确认的还款：用销帐金额，只抵同币别 ----

    @Test
    void aConfirmedRepaymentReducesTheDebt() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        confirmed(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 200_000));
    }

    @Test
    void aFullyRepaidDebtDisappears() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        confirmed(s.tripId, s.aliceMember, s.meMember, "JPY", 300_000);

        assertThat(overview(s.meUser, s.tripId, "")).isEmpty();
    }

    @Test
    void aConfirmedRepaymentUsesTheClearedAmountNotThePaidAmount() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        // 销帐 JPY 1000，实际转了 HKD 50
        repayment(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000, "HKD", 5_000, "confirmed", false, uuid(), null);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 200_000));
    }

    @Test
    void aConfirmedRepaymentOnlyReducesItsOwnCurrency() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        expense(s.tripId, s.meMember, "HKD", s.aliceMember, 10_000);
        confirmed(s.tripId, s.aliceMember, s.meMember, "HKD", 4_000);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(
                owes(s.aliceMember, s.meMember, "HKD", 6_000),
                owes(s.aliceMember, s.meMember, "JPY", 100_000));
    }

    @Test
    void aRepaymentInTheOppositeDirectionAddsToTheDebt() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        confirmed(s.tripId, s.meMember, s.aliceMember, "JPY", 50_000);   // me 付给 alice，反而增加 alice 欠 me 的

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 150_000));
    }

    @Test
    void anOverpaidDebtFlipsDirection() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        confirmed(s.tripId, s.aliceMember, s.meMember, "JPY", 150_000);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.meMember, s.aliceMember, "JPY", 50_000));
    }

    @Test
    void deletingTheExpenseOfAConfirmedRepaymentFlipsTheNet() throws Exception {
        Scene s = new Scene();
        long expenseId = expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        confirmed(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
        assertThat(overview(s.meUser, s.tripId, "")).isEmpty();

        softDeleteExpense(expenseId);

        // 欠款随支出消失，已确认的还款还在：me 反过来欠 alice
        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.meMember, s.aliceMember, "JPY", 100_000));
    }

    // ---- 待确认的还款：不抵债，另列 pendingMinor ----

    @Test
    void aPendingRepaymentDoesNotReduceTheDebtButIsListedAsPending() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 300_000, 100_000));
    }

    @Test
    void aCancelledRepaymentChangesNothing() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        cancelled(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 300_000, 0));
    }

    @Test
    void pendingMinorCountsOnlyTheSameDirectionAndTheSameCurrency() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        expense(s.tripId, s.meMember, "HKD", s.aliceMember, 20_000);
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 50_000);
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 50_000);   // 两条加起来
        pending(s.tripId, s.aliceMember, s.meMember, "HKD", 7_000);
        pending(s.tripId, s.meMember, s.aliceMember, "JPY", 30_000);   // 反方向的不算
        pending(s.tripId, s.bobMember, s.meMember, "JPY", 40_000);     // 别人的不算
        cancelled(s.tripId, s.aliceMember, s.meMember, "JPY", 10_000); // 已取消的不算

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(
                owes(s.aliceMember, s.meMember, "HKD", 20_000, 7_000),
                owes(s.aliceMember, s.meMember, "JPY", 300_000, 100_000));
    }

    @Test
    void pendingMinorCanExceedTheDebt() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 150_000);

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 100_000, 150_000));
    }

    @Test
    void aPendingRepaymentWithoutDebtShowsNothing() throws Exception {
        Scene s = new Scene();
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        assertThat(overview(s.meUser, s.tripId, "")).isEmpty();
    }

    @Test
    void pendingMinorIsVisibleToEveryActiveMember() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        // carol 和这笔还款无关，也看得到
        assertThat(overview(s.carolUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 300_000, 100_000));
    }

    // ---- memberId 筛选 ----

    @Test
    void memberIdKeepsOnlyTheDebtsOfThatMemberInBothDirections() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);    // alice 欠 me
        expense(s.tripId, s.aliceMember, "JPY", s.bobMember, 200_000);   // bob 欠 alice
        expense(s.tripId, s.bobMember, "JPY", s.carolMember, 300_000);   // carol 欠 bob，和 alice 无关

        assertThat(overview(s.carolUser, s.tripId, "memberId=" + s.aliceMember)).containsExactly(
                owes(s.aliceMember, s.meMember, "JPY", 100_000),
                owes(s.bobMember, s.aliceMember, "JPY", 200_000));
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
        expense(tripId, owner, "JPY", memberX, 100_000);
        expense(tripId, owner, "JPY", memberY, 200_000);

        assertThat(overview(ownerUser, tripId, "memberId=" + EXPLICIT_ID)).containsExactly(owes(memberY, owner, "JPY", 200_000));
    }

    @Test
    void memberIdCanBeAMemberWhoLeft() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.aliceMember, "JPY", s.bobMember, 100_000);
        expense(s.tripId, s.meMember, "JPY", s.carolMember, 50_000);
        leave(s.bobMember);

        assertThat(overview(s.meUser, s.tripId, "memberId=" + s.bobMember)).containsExactly(owes(s.bobMember, s.aliceMember, "JPY", 100_000));
    }

    @Test
    void aMemberIdOutsideTheTripGivesAnEmptyArray() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);

        assertThat(overview(s.meUser, s.tripId, "memberId=" + other.aliceMember)).isEmpty();
        assertThat(overview(s.meUser, s.tripId, "memberId=999999999")).isEmpty();
    }

    @Test
    void aMemberIdThatIsNotANumberIs400() throws Exception {
        Scene s = new Scene();

        debts(s.meUser, s.tripId, "memberId=abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- 访问 ----

    @Test
    void everyActiveMemberSeesTheWholeOverview() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);

        assertThat(overview(s.meUser, s.tripId, "")).hasSize(1);
        assertThat(overview(s.aliceUser, s.tripId, "")).hasSize(1);
        assertThat(overview(s.bobUser, s.tripId, "")).hasSize(1);
        assertThat(overview(s.carolUser, s.tripId, "")).hasSize(1);
    }

    @Test
    void aNonMemberGets404() throws Exception {
        Scene s = new Scene();
        long outsider = user("r-outsider");

        debts(outsider, s.tripId, "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aMemberWhoLeftGets404() throws Exception {
        Scene s = new Scene();
        leave(s.aliceMember);

        debts(s.aliceUser, s.tripId, "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aDeletedTripGets404() throws Exception {
        Scene s = new Scene();
        deleteTrip(s.tripId);

        debts(s.meUser, s.tripId, "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void withoutIdentityIs401() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(get("/trips/" + s.tripId + "/debts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
