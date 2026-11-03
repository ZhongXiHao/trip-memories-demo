package com.cshlands.api.repayment;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips/{tripId}/repayments/{repaymentId}/confirm 和 .../groups/{settlementGroupId}/confirm：收款方确认。
 * 默认场景：alice（付款方）向 me（收款方）还，库里是一条待确认的 JPY 1000。
 * 在籍成员都看得见行程里的还款，所以"不是收款方"是 403；不是在籍成员才是 404。
 * 成员 memberId 升序：me < alice < bob < carol。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class RepaymentConfirmTest extends RepaymentTestBase {

    private long pendingFromAliceToMe(Scene s) {
        return pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
    }

    // ---- 单条 ----

    @Test
    void thePayeeConfirmsASingleRepayment() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);

        confirmRepayment(s.meUser, s.tripId, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.confirmedAt").isString())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.fromMemberId").value(s.aliceMember))
                .andExpect(jsonPath("$.toMemberId").value(s.meMember))
                .andExpect(jsonPath("$.createdByMemberId").value(s.aliceMember))
                .andExpect(jsonPath("$.clearedAmountMinor").value(100_000));

        RepaymentRow row = rowOf(s.tripId, id);
        assertThat(row.status()).isEqualTo("confirmed");
        assertThat(row.confirmed()).isTrue();
        assertThat(row.deleted()).isFalse();
        assertThat(row.version()).isEqualTo(1);
    }

    @Test
    void theResponseObjectHasExactlyTheContractFields() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);

        String json = confirmRepayment(s.meUser, s.tripId, id).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys(
                "id", "settlementGroupId", "expenseId", "fromMemberId", "toMemberId",
                "clearedAmountMinor", "clearedCurrency", "paidAmountMinor", "paidCurrency",
                "status", "confirmedAt", "note", "createdByMemberId", "version", "createdAt");
    }

    @Test
    void confirmingTurnsPendingMinorIntoARealReductionInTheOverview() throws Exception {
        // 端到端：依赖欠款总览接口
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        long id = pendingFromAliceToMe(s);
        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 300_000, 100_000));

        confirmRepayment(s.meUser, s.tripId, id).andExpect(status().isOk());

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 200_000, 0));
    }

    @Test
    void confirmingAnAlreadyConfirmedRepaymentIs200AndChangesNothing() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);
        confirmRepayment(s.meUser, s.tripId, id).andExpect(status().isOk());

        confirmRepayment(s.meUser, s.tripId, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(rowOf(s.tripId, id).version()).isEqualTo(1);
    }

    @Test
    void aRepaymentRecordedAsReceivedIsAlreadyConfirmedSoConfirmingIsA200NoOp() throws Exception {
        Scene s = new Scene();
        long id = confirmed(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        confirmRepayment(s.meUser, s.tripId, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void aCancelledRepaymentCannotBeConfirmed() throws Exception {
        Scene s = new Scene();
        long id = cancelled(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        confirmRepayment(s.meUser, s.tripId, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        RepaymentRow row = rowOf(s.tripId, id);
        assertThat(row.confirmed()).isFalse();
        assertThat(row.deleted()).isTrue();
    }

    @Test
    void confirmingDoesNotCheckTheLimitAgain() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        long id = pending(s.tripId, s.aliceMember, s.meMember, "JPY", 150_000);   // 比欠款还多

        confirmRepayment(s.meUser, s.tripId, id).andExpect(status().isOk());

        // 钱已经收到，净额反向：me 反过来欠 alice 50_000
        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.meMember, s.aliceMember, "JPY", 50_000));
    }

    @Test
    void confirmingOneRepaymentLeavesTheOthersAlone() throws Exception {
        Scene s = new Scene();
        long target = pendingFromAliceToMe(s);
        long other = pendingFromAliceToMe(s);
        long othersRepayment = pending(s.tripId, s.bobMember, s.meMember, "JPY", 100_000);

        confirmRepayment(s.meUser, s.tripId, target).andExpect(status().isOk());

        assertThat(rowOf(s.tripId, other).status()).isEqualTo("pending");
        assertThat(rowOf(s.tripId, othersRepayment).status()).isEqualTo("pending");
    }

    // ---- 单条：谁能确认 ----

    @Test
    void onlyThePayeeCanConfirmEveryoneElseWhoIsAMemberGets403() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);

        // 付款方自己、不相干的第三人
        for (long user : new long[]{s.aliceUser, s.bobUser}) {
            confirmRepayment(user, s.tripId, id)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(rowOf(s.tripId, id).status()).isEqualTo("pending");
    }

    @Test
    void theOwnerHasNoSpecialRightToConfirm() throws Exception {
        Scene s = new Scene();   // me 是 Owner
        long id = pending(s.tripId, s.aliceMember, s.bobMember, "JPY", 100_000);

        confirmRepayment(s.meUser, s.tripId, id).andExpect(status().isForbidden());
        assertThat(rowOf(s.tripId, id).status()).isEqualTo("pending");
    }

    @Test
    void thePayeeCanStillConfirmAfterThePayerLeft() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);
        leave(s.aliceMember);

        confirmRepayment(s.meUser, s.tripId, id).andExpect(status().isOk());
    }

    @Test
    void aPayeeWhoLeftCannotConfirmAnythingItIs404() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);
        leave(s.meMember);

        confirmRepayment(s.meUser, s.tripId, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(rowOf(s.tripId, id).status()).isEqualTo("pending");
    }

    @Test
    void aNonMemberGets404() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);
        long outsider = user("r-outsider");

        confirmRepayment(outsider, s.tripId, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aDeletedTripGets404() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);
        deleteTrip(s.tripId);

        confirmRepayment(s.meUser, s.tripId, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void withoutIdentityIs401() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);

        mockMvc.perform(post("/trips/" + s.tripId + "/repayments/" + id + "/confirm"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anUnknownIdOrAnIdFromAnotherTripIs404() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();
        long othersId = pending(other.tripId, other.aliceMember, other.meMember, "JPY", 100_000);

        confirmRepayment(s.meUser, s.tripId, 999_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        confirmRepayment(s.meUser, s.tripId, othersId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(rowOf(other.tripId, othersId).status()).isEqualTo("pending");
    }

    @Test
    void anIdThatIsNotANumberIs400() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(post("/trips/" + s.tripId + "/repayments/abc/confirm").header("X-User-Id", s.meUser))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void thePayeeIsTheMemberIdNotTheUserId() throws Exception {
        long ownerUser = user("r-owner");
        long tripId = trip(ownerUser);
        long owner = memberId(tripId, ownerUser);
        // X 的 userId 恰好等于 Y 的 memberId；收款方是 Y
        long userX = userWithId(EXPLICIT_ID, "r-x");
        member(tripId, userX, "member", "active");
        long userY = user("r-y");
        long memberY = memberWithId(EXPLICIT_ID, tripId, userY, "member", "active");
        long id = pending(tripId, owner, memberY, "JPY", 100_000);

        // X 的 userId 等于收款方的 memberId，但他不是收款方
        confirmRepayment(userX, tripId, id).andExpect(status().isForbidden());
        assertThat(rowOf(tripId, id).status()).isEqualTo("pending");
        confirmRepayment(userY, tripId, id).andExpect(status().isOk());
    }

    // ---- 单条：并发 ----

    @Test
    void twoSimultaneousConfirmsBothSucceedAndTheVersionGoesUpOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            long id = pendingFromAliceToMe(s);

            List<MvcResult> results = runConcurrently(2, () -> confirmRepayment(s.meUser, s.tripId, id).andReturn());

            assertThat(statuses(results)).containsExactly(200, 200);
            assertThat(rowOf(s.tripId, id).version()).isEqualTo(1);
        }
    }

    // ---- 整组 ----

    @Test
    void confirmingAGroupConfirmsEveryPendingRowOfIt() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "pending", false);

        String json = confirmGroup(s.meUser, s.tripId, g).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys("items");
        assertThat(statusByCurrency(json)).containsOnly(Map.entry("JPY", "confirmed"), Map.entry("HKD", "confirmed"));
        assertThat(JsonPath.<List<Number>>read(json, "$.items[*].version")).allMatch(v -> v.intValue() == 1);
        assertThat(repaymentRows(s.tripId, g)).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("confirmed");
            assertThat(r.confirmed()).isTrue();
        });
    }

    @Test
    void aGroupWithAlreadyConfirmedAndCancelledRowsStillConfirmsTheRest() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "confirmed", false);
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "USD", 1_000, "pending", true);

        String json = confirmGroup(s.meUser, s.tripId, g).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 已取消的行也在响应里，status 是 cancelled，不会被确认
        assertThat(statusByCurrency(json)).containsOnly(
                Map.entry("JPY", "confirmed"), Map.entry("HKD", "confirmed"), Map.entry("USD", "cancelled"));
        List<RepaymentRow> rows = repaymentRows(s.tripId, g);
        assertThat(rows).filteredOn(r -> r.clearedCurrency().equals("USD")).singleElement()
                .satisfies(r -> {
                    assertThat(r.confirmed()).isFalse();
                    assertThat(r.deleted()).isTrue();
                    assertThat(r.version()).isZero();
                });
        // 本来就已确认的那一行没有被动过
        assertThat(rows).filteredOn(r -> r.clearedCurrency().equals("HKD")).singleElement()
                .satisfies(r -> assertThat(r.version()).isZero());
    }

    @Test
    void confirmingAGroupThatIsAlreadyFullyConfirmedIs200() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "confirmed", false);

        String json = confirmGroup(s.meUser, s.tripId, g).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(statusByCurrency(json)).containsOnly(Map.entry("JPY", "confirmed"));
    }

    @Test
    void aGroupThatIsUnknownOrWhollyCancelledIs404() throws Exception {
        Scene s = new Scene();
        String cancelledGroup = uuid();
        groupRow(s.tripId, cancelledGroup, s.aliceMember, s.meMember, "JPY", 100_000, "pending", true);
        groupRow(s.tripId, cancelledGroup, s.aliceMember, s.meMember, "HKD", 2_000, "pending", true);

        confirmGroup(s.meUser, s.tripId, uuid())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        confirmGroup(s.meUser, s.tripId, cancelledGroup)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void onlyThePayeeOfTheGroupCanConfirmIt() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "pending", false);

        confirmGroup(s.aliceUser, s.tripId, g).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        confirmGroup(s.bobUser, s.tripId, g).andExpect(status().isForbidden());
        assertThat(repaymentRows(s.tripId, g)).extracting(RepaymentRow::status).containsOnly("pending");
    }

    @Test
    void aGroupIsLookedUpInsideTheTripOnly() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();
        String g = uuid();   // 两个行程里碰巧用了同一个 settlementGroupId
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        groupRow(other.tripId, g, other.aliceMember, other.meMember, "JPY", 100_000, "pending", false);

        confirmGroup(s.meUser, s.tripId, g).andExpect(status().isOk());

        assertThat(repaymentRows(s.tripId, g)).extracting(RepaymentRow::status).containsOnly("confirmed");
        assertThat(repaymentRows(other.tripId, g)).extracting(RepaymentRow::status).containsOnly("pending");
        // 别的行程的成员拿着这个 id 也碰不到
        Scene third = new Scene();
        confirmGroup(third.meUser, third.tripId, g).andExpect(status().isNotFound());
    }

    @Test
    void aNonMemberOrALeftPayeeConfirmingAGroupGets404() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        long outsider = user("r-outsider");

        confirmGroup(outsider, s.tripId, g).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        leave(s.meMember);
        confirmGroup(s.meUser, s.tripId, g).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(repaymentRows(s.tripId, g)).extracting(RepaymentRow::status).containsOnly("pending");
    }

    @Test
    void withoutIdentityConfirmingAGroupIs401() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(post("/trips/" + s.tripId + "/repayments/groups/" + uuid() + "/confirm"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSingleRowOfAGroupCanBeConfirmedOnItsOwn() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        long jpy = groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "pending", false);

        confirmRepayment(s.meUser, s.tripId, jpy).andExpect(status().isOk());

        assertThat(repaymentRows(s.tripId, g)).extracting(RepaymentRow::clearedCurrency, RepaymentRow::status)
                .containsExactly(tuple("HKD", "pending"), tuple("JPY", "confirmed"));
    }

    @Test
    void confirmingAGroupAndCancellingOneRowOfItAtTheSameTimeNeverConfirmsACancelledRow() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            String g = uuid();
            long jpy = groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
            groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "pending", false);

            List<Callable<MvcResult>> calls = List.of(
                    () -> confirmGroup(s.meUser, s.tripId, g).andReturn(),
                    () -> cancelRepayment(s.aliceUser, s.tripId, jpy).andReturn());
            List<MvcResult> results = runAll(calls);

            // 整组确认永远是 200；JPY 那一行要么先被确认（取消得到 409），要么先被取消（保持已取消）
            assertThat(results.get(0).getResponse().getStatus()).isEqualTo(200);
            RepaymentRow row = rowOf(s.tripId, jpy);
            assertThat(row.confirmed() && row.deleted()).as("不能既已确认又已取消").isFalse();
            assertThat(results.get(1).getResponse().getStatus()).isEqualTo(row.confirmed() ? 409 : 200);
            assertThat(rowOf(s.tripId, groupRowId(s, g, "HKD")).status()).isEqualTo("confirmed");
        }
    }

    private long groupRowId(Scene s, String group, String currency) {
        return repaymentRows(s.tripId, group).stream()
                .filter(r -> r.clearedCurrency().equals(currency)).findFirst().orElseThrow().id();
    }
}
