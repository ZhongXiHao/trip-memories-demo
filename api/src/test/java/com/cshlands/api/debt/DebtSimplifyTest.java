package com.cshlands.api.debt;

import com.cshlands.api.MySqlTestContainerConfig;
import com.cshlands.api.repayment.RepaymentTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /trips/{tripId}/debts?simplify=true：传递化简，给出一组"建议转账"。
 * 契约规定的做法：每个币别分别算成员余额，反复拿余额最大的债权人和余额最大的债务人对冲，
 * 金额取较小者，余额相同取 memberId 小的。所以结果是确定的，可以逐项断言。
 * 成员 memberId 升序：me < alice < bob < carol。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class DebtSimplifyTest extends RepaymentTestBase {

    @Test
    void simplifyFalseIsTheDefault() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 100_000);

        assertThat(overview(s.meUser, s.tripId, "simplify=false")).isEqualTo(overview(s.meUser, s.tripId, ""));
        assertThat(overview(s.meUser, s.tripId, "")).hasSize(2);
    }

    @Test
    void aChainIsMergedIntoOneTransfer() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);   // alice 欠 bob
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 100_000);   // bob 欠 carol

        assertThat(overview(s.meUser, s.tripId, "simplify=true"))
                .containsExactly(owes(s.aliceMember, s.carolMember, "JPY", 100_000));
    }

    @Test
    void balancesAreNettedFromConfirmedRepaymentsToo() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);   // alice 欠 bob 100_000
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 100_000);   // bob 欠 carol 100_000
        confirmed(s.tripId, s.aliceMember, s.bobMember, "JPY", 40_000);  // alice 还了 bob 40_000
        // 余额：alice −60_000，bob +60_000 −100_000 = −40_000，carol +100_000

        assertThat(overview(s.meUser, s.tripId, "simplify=true")).containsExactly(
                owes(s.aliceMember, s.carolMember, "JPY", 60_000),
                owes(s.bobMember, s.carolMember, "JPY", 40_000));
    }

    @Test
    void largestCreditorMeetsLargestDebtorAndTiesGoToTheSmallerMemberId() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 500_000);    // alice 欠 bob 500_000
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 200_000);    // bob 欠 carol 200_000
        expense(s.tripId, s.meMember, "JPY", s.carolMember, 100_000);      // carol 欠 me 100_000
        // 余额：alice −500_000，bob +300_000，carol +100_000，me +100_000
        // 1. 最大债权人 bob(300_000) × 最大债务人 alice：alice→bob 300_000，alice 还差 200_000
        // 2. 债权人 carol 和 me 都是 100_000，取 memberId 小的 me：alice→me 100_000
        // 3. 剩下 carol：alice→carol 100_000

        assertThat(overview(s.meUser, s.tripId, "simplify=true")).containsExactly(
                owes(s.aliceMember, s.meMember, "JPY", 100_000),
                owes(s.aliceMember, s.bobMember, "JPY", 300_000),
                owes(s.aliceMember, s.carolMember, "JPY", 100_000));
    }

    @Test
    void theLargestIsPickedFirstOnBothSidesNotTheSmallest() throws Exception {
        Scene s = new Scene();
        long daveUser = user("r-dave");
        long dave = member(s.tripId, daveUser, "member", "active");   // memberId 比 carol 大
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000, s.bobMember, 50_000);   // alice、bob 欠 me
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 30_000);                        // bob 欠 carol
        expense(s.tripId, dave, "JPY", s.bobMember, 20_000);                                 // bob 欠 dave
        // 余额：债务人 alice 100_000、bob 100_000（平手取 memberId 小的 alice）；债权人 me 150_000、carol 30_000、dave 20_000
        // 1. alice(100_000) × me(150_000)：alice→me 100_000，me 剩 50_000
        // 2. bob(100_000) × me(50_000)：bob→me 50_000，bob 剩 50_000
        // 3. bob × carol(30_000)：bob→carol 30_000
        // 4. bob(20_000) × dave(20_000)：bob→dave 20_000
        // 如果每次取的是最小的，会先把 dave、carol 各配给 alice，结果是另一组转账

        assertThat(overview(s.meUser, s.tripId, "simplify=true")).containsExactly(
                owes(s.aliceMember, s.meMember, "JPY", 100_000),
                owes(s.bobMember, s.meMember, "JPY", 50_000),
                owes(s.bobMember, s.carolMember, "JPY", 30_000),
                owes(s.bobMember, dave, "JPY", 20_000));
    }

    @Test
    void currenciesAreSimplifiedSeparately() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);   // JPY：alice 欠 bob
        expense(s.tripId, s.carolMember, "HKD", s.bobMember, 5_000);     // HKD：bob 欠 carol

        // 不同币别不会合并成 alice→carol
        assertThat(overview(s.meUser, s.tripId, "simplify=true")).containsExactly(
                owes(s.aliceMember, s.bobMember, "JPY", 100_000),
                owes(s.bobMember, s.carolMember, "HKD", 5_000));
    }

    @Test
    void debtsThatAlreadyCancelOutLeaveNothing() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);   // alice 欠 bob
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 100_000);   // bob 欠 carol
        expense(s.tripId, s.aliceMember, "JPY", s.carolMember, 100_000); // carol 欠 alice：一圈，每人余额都是 0

        assertThat(overview(s.meUser, s.tripId, "simplify=true")).isEmpty();
    }

    @Test
    void suggestedTransfersCarryNoPendingMinor() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 100_000);
        pending(s.tripId, s.aliceMember, s.bobMember, "JPY", 30_000);
        pending(s.tripId, s.aliceMember, s.carolMember, "JPY", 20_000);

        assertThat(overview(s.meUser, s.tripId, "simplify=true"))
                .containsExactly(owes(s.aliceMember, s.carolMember, "JPY", 100_000, 0));
    }

    @Test
    void memberIdFiltersTheSimplifiedResultNotTheOriginalDebts() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 100_000);

        // 不化简时 bob 夹在中间有两项；化简后 alice→carol 不涉及 bob，所以是空的
        assertThat(overview(s.meUser, s.tripId, "memberId=" + s.bobMember)).hasSize(2);
        assertThat(overview(s.meUser, s.tripId, "memberId=" + s.bobMember + "&simplify=true")).isEmpty();
        assertThat(overview(s.meUser, s.tripId, "memberId=" + s.carolMember + "&simplify=true"))
                .containsExactly(owes(s.aliceMember, s.carolMember, "JPY", 100_000));
    }

    @Test
    void everyMemberGetsTheSameSimplifiedAnswer() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 100_000);

        assertThat(overview(s.carolUser, s.tripId, "simplify=true"))
                .isEqualTo(overview(s.meUser, s.tripId, "simplify=true"))
                .containsExactly(owes(s.aliceMember, s.carolMember, "JPY", 100_000));
    }

    @Test
    void aMemberWhoLeftTakesPartInTheSimplification() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 100_000);
        leave(s.bobMember);

        assertThat(overview(s.meUser, s.tripId, "simplify=true"))
                .containsExactly(owes(s.aliceMember, s.carolMember, "JPY", 100_000));
    }

    @Test
    void otherValuesOfSimplifyAre400() throws Exception {
        Scene s = new Scene();

        debts(s.meUser, s.tripId, "simplify=maybe")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
