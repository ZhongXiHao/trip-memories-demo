package com.cshlands.api.repayment;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips/{tripId}/repayments/{repaymentId}/cancel 和 .../groups/{settlementGroupId}/cancel：取消待确认的还款。
 * 库里取消 = pending 加 deleted_at；接口上是 status = cancelled，所以响应体不用另外加字段。
 * 付款方、收款方任一方都可以取消；其他在籍成员 403；不是在籍成员 404。
 * 默认场景：alice（付款方）向 me（收款方）还，库里是一条待确认的 JPY 1000。成员 memberId 升序：me < alice < bob < carol。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class RepaymentCancelTest extends RepaymentTestBase {

    private long pendingFromAliceToMe(Scene s) {
        return pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
    }

    // ---- 单条 ----

    @Test
    void thePayerCancelsAPendingRepayment() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);

        cancelRepayment(s.aliceUser, s.tripId, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.clearedAmountMinor").value(100_000));

        RepaymentRow row = rowOf(s.tripId, id);
        assertThat(row.deleted()).isTrue();
        assertThat(row.confirmed()).isFalse();
        assertThat(row.version()).isEqualTo(1);
    }

    @Test
    void thePayeeCancelsAPendingRepaymentToo() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);

        cancelRepayment(s.meUser, s.tripId, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
        assertThat(rowOf(s.tripId, id).deleted()).isTrue();
    }

    @Test
    void theResponseObjectHasExactlyTheContractFields() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);

        String json = cancelRepayment(s.aliceUser, s.tripId, id).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 值是 null 的字段（confirmedAt）也要在
        assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys(
                "id", "settlementGroupId", "expenseId", "fromMemberId", "toMemberId",
                "clearedAmountMinor", "clearedCurrency", "paidAmountMinor", "paidCurrency",
                "status", "confirmedAt", "note", "createdByMemberId", "version", "createdAt");
    }

    @Test
    void aConfirmedRepaymentCannotBeCancelled() throws Exception {
        Scene s = new Scene();
        long confirmedByPayee = confirmed(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);
        long confirmedLater = pendingFromAliceToMe(s);
        confirmRepayment(s.meUser, s.tripId, confirmedLater).andExpect(status().isOk());

        for (long id : new long[]{confirmedByPayee, confirmedLater}) {
            cancelRepayment(s.aliceUser, s.tripId, id)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REPAYMENT_ALREADY_CONFIRMED"));
            cancelRepayment(s.meUser, s.tripId, id).andExpect(status().isConflict());
            RepaymentRow row = rowOf(s.tripId, id);
            assertThat(row.deleted()).isFalse();
            assertThat(row.status()).isEqualTo("confirmed");
        }
    }

    @Test
    void cancellingAnAlreadyCancelledRepaymentIs200AndChangesNothing() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);
        cancelRepayment(s.aliceUser, s.tripId, id).andExpect(status().isOk());

        // 另一方再取消一次
        cancelRepayment(s.meUser, s.tripId, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(rowOf(s.tripId, id).version()).isEqualTo(1);
    }

    @Test
    void cancellingARepaymentThatWasCancelledBeforeIs200() throws Exception {
        Scene s = new Scene();
        long id = cancelled(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        cancelRepayment(s.aliceUser, s.tripId, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void cancellingOneRepaymentLeavesTheOthersAlone() throws Exception {
        Scene s = new Scene();
        long target = pendingFromAliceToMe(s);
        long other = pendingFromAliceToMe(s);
        long othersRepayment = pending(s.tripId, s.bobMember, s.meMember, "JPY", 100_000);

        cancelRepayment(s.aliceUser, s.tripId, target).andExpect(status().isOk());

        assertThat(rowOf(s.tripId, other).deleted()).isFalse();
        assertThat(rowOf(s.tripId, othersRepayment).deleted()).isFalse();
    }

    // ---- 取消之后的效果（端到端，依赖别的接口） ----

    @Test
    void cancellingTakesThePendingAmountOutOfTheOverview() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        long id = pendingFromAliceToMe(s);
        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 300_000, 100_000));

        cancelRepayment(s.aliceUser, s.tripId, id).andExpect(status().isOk());

        // 欠款本身不变，待确认没了
        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 300_000, 0));
    }

    @Test
    void cancellingGivesTheLimitBack() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        long id = pendingFromAliceToMe(s);   // 占满额度
        RepaymentBody again = RepaymentBody.of(uuid(), s.aliceMember, s.meMember, "JPY", item("JPY", 100_000));
        createRepayment(s.aliceUser, s.tripId, again)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPAYMENT_EXCEEDS_DEBT"));

        cancelRepayment(s.aliceUser, s.tripId, id).andExpect(status().isOk());

        createRepayment(s.aliceUser, s.tripId, again).andExpect(status().isCreated());
    }

    @Test
    void aCancelledRepaymentDisappearsFromTheList() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);
        long kept = pendingFromAliceToMe(s);

        cancelRepayment(s.aliceUser, s.tripId, id).andExpect(status().isOk());

        String json = listRepayments(s.meUser, s.tripId, "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(idsIn(json)).containsExactly(kept);
    }

    // ---- 单条：谁能取消 ----

    @Test
    void aThirdMemberCannotCancelIt() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);

        cancelRepayment(s.bobUser, s.tripId, id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(rowOf(s.tripId, id).deleted()).isFalse();
    }

    @Test
    void theOwnerHasNoSpecialRightToCancel() throws Exception {
        Scene s = new Scene();   // me 是 Owner
        long id = pending(s.tripId, s.aliceMember, s.bobMember, "JPY", 100_000);

        cancelRepayment(s.meUser, s.tripId, id).andExpect(status().isForbidden());
        assertThat(rowOf(s.tripId, id).deleted()).isFalse();
    }

    @Test
    void thePayeeCanCancelAfterThePayerLeft() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);
        leave(s.aliceMember);

        cancelRepayment(s.meUser, s.tripId, id).andExpect(status().isOk());
    }

    @Test
    void thePayerCanCancelAfterThePayeeLeftWhoCouldNotConfirmAnyMore() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);
        leave(s.meMember);

        // 收款方退出后进不来了
        cancelRepayment(s.meUser, s.tripId, id).andExpect(status().isNotFound());
        // 付款方还能取消
        cancelRepayment(s.aliceUser, s.tripId, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void aNonMemberGets404() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);
        long outsider = user("r-outsider");

        cancelRepayment(outsider, s.tripId, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aDeletedTripGets404() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);
        deleteTrip(s.tripId);

        cancelRepayment(s.aliceUser, s.tripId, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void withoutIdentityIs401() throws Exception {
        Scene s = new Scene();
        long id = pendingFromAliceToMe(s);

        mockMvc.perform(post("/trips/" + s.tripId + "/repayments/" + id + "/cancel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anUnknownIdOrAnIdFromAnotherTripIs404() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();
        long othersId = pending(other.tripId, other.aliceMember, other.meMember, "JPY", 100_000);

        cancelRepayment(s.aliceUser, s.tripId, 999_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        cancelRepayment(s.aliceUser, s.tripId, othersId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(rowOf(other.tripId, othersId).deleted()).isFalse();
    }

    @Test
    void anIdThatIsNotANumberIs400() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(post("/trips/" + s.tripId + "/repayments/abc/cancel").header("X-User-Id", s.aliceUser))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void thePartiesAreMemberIdsNotUserIds() throws Exception {
        long ownerUser = user("r-owner");
        long tripId = trip(ownerUser);
        long owner = memberId(tripId, ownerUser);
        // X 的 userId 恰好等于 Y 的 memberId；付款方是 Y
        long userX = userWithId(EXPLICIT_ID, "r-x");
        member(tripId, userX, "member", "active");
        long userY = user("r-y");
        long memberY = memberWithId(EXPLICIT_ID, tripId, userY, "member", "active");
        long id = pending(tripId, memberY, owner, "JPY", 100_000);

        cancelRepayment(userX, tripId, id).andExpect(status().isForbidden());
        assertThat(rowOf(tripId, id).deleted()).isFalse();
        cancelRepayment(userY, tripId, id).andExpect(status().isOk());
    }

    // ---- 单条：并发 ----

    @Test
    void twoSimultaneousCancelsBothSucceedAndTheVersionGoesUpOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            long id = pendingFromAliceToMe(s);

            List<MvcResult> results = runAll(List.of(
                    () -> cancelRepayment(s.aliceUser, s.tripId, id).andReturn(),
                    () -> cancelRepayment(s.meUser, s.tripId, id).andReturn()));

            assertThat(statuses(results)).containsExactly(200, 200);
            assertThat(rowOf(s.tripId, id).version()).isEqualTo(1);
        }
    }

    @Test
    void aConfirmAndACancelAtTheSameTimeLeaveExactlyOneOutcome() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            long id = pendingFromAliceToMe(s);

            List<MvcResult> results = runAll(List.of(
                    () -> confirmRepayment(s.meUser, s.tripId, id).andReturn(),
                    () -> cancelRepayment(s.aliceUser, s.tripId, id).andReturn()));

            RepaymentRow row = rowOf(s.tripId, id);
            assertThat(row.confirmed() && row.deleted()).as("不能既已确认又已取消").isFalse();
            if (row.confirmed()) {
                // 先确认：取消失败
                assertThat(statuses(results)).containsExactly(200, 409);
            } else {
                // 先取消：确认拿到 404
                assertThat(row.deleted()).isTrue();
                assertThat(statuses(results)).containsExactly(404, 200);
            }
            assertThat(row.version()).isEqualTo(1);
        }
    }

    // ---- 整组 ----

    @Test
    void cancellingAGroupCancelsEveryPendingRowOfIt() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "pending", false);

        String json = cancelGroup(s.aliceUser, s.tripId, g).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys("items");
        assertThat(statusByCurrency(json)).containsOnly(Map.entry("JPY", "cancelled"), Map.entry("HKD", "cancelled"));
        assertThat(repaymentRows(s.tripId, g)).allSatisfy(r -> {
            assertThat(r.deleted()).isTrue();
            assertThat(r.version()).isEqualTo(1);
        });
    }

    @Test
    void thePayeeCanCancelAGroupToo() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);

        cancelGroup(s.meUser, s.tripId, g).andExpect(status().isOk());

        assertThat(repaymentRows(s.tripId, g)).extracting(RepaymentRow::deleted).containsOnly(true);
    }

    @Test
    void aGroupWithAConfirmedRowCancelsTheRestAndKeepsTheConfirmedOne() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "confirmed", false);

        String json = cancelGroup(s.aliceUser, s.tripId, g).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(statusByCurrency(json)).containsOnly(Map.entry("JPY", "cancelled"), Map.entry("HKD", "confirmed"));
        List<RepaymentRow> rows = repaymentRows(s.tripId, g);
        assertThat(rows).filteredOn(r -> r.clearedCurrency().equals("HKD")).singleElement().satisfies(r -> {
            assertThat(r.deleted()).isFalse();
            assertThat(r.status()).isEqualTo("confirmed");
        });
    }

    @Test
    void aGroupWithNothingLeftToCancelButAConfirmedRowIs409() throws Exception {
        Scene s = new Scene();
        String allConfirmed = uuid();
        groupRow(s.tripId, allConfirmed, s.aliceMember, s.meMember, "JPY", 100_000, "confirmed", false);
        groupRow(s.tripId, allConfirmed, s.aliceMember, s.meMember, "HKD", 2_000, "confirmed", false);
        String cancelledAndConfirmed = uuid();
        groupRow(s.tripId, cancelledAndConfirmed, s.aliceMember, s.meMember, "JPY", 100_000, "pending", true);
        groupRow(s.tripId, cancelledAndConfirmed, s.aliceMember, s.meMember, "HKD", 2_000, "confirmed", false);

        for (String g : List.of(allConfirmed, cancelledAndConfirmed)) {
            cancelGroup(s.aliceUser, s.tripId, g)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REPAYMENT_ALREADY_CONFIRMED"));
        }
        assertThat(repaymentRows(s.tripId)).filteredOn(RepaymentRow::confirmed).extracting(RepaymentRow::deleted)
                .containsOnly(false);
    }

    @Test
    void cancellingAWhollyCancelledGroupAgainIs200AndChangesNothing() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", true);
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "pending", true);

        String json = cancelGroup(s.aliceUser, s.tripId, g).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(statusByCurrency(json)).containsOnly(Map.entry("JPY", "cancelled"), Map.entry("HKD", "cancelled"));
        assertThat(repaymentRows(s.tripId, g)).extracting(RepaymentRow::version).containsOnly(0);
    }

    @Test
    void anUnknownGroupIs404() throws Exception {
        Scene s = new Scene();

        cancelGroup(s.aliceUser, s.tripId, uuid())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aThirdMemberCannotCancelAGroup() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);

        cancelGroup(s.bobUser, s.tripId, g)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(repaymentRows(s.tripId, g)).extracting(RepaymentRow::deleted).containsOnly(false);
    }

    @Test
    void aGroupIsLookedUpInsideTheTripOnly() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();
        String g = uuid();   // 两个行程里碰巧用了同一个 settlementGroupId
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        groupRow(other.tripId, g, other.aliceMember, other.meMember, "JPY", 100_000, "pending", false);

        cancelGroup(s.aliceUser, s.tripId, g).andExpect(status().isOk());

        assertThat(repaymentRows(s.tripId, g)).extracting(RepaymentRow::deleted).containsOnly(true);
        assertThat(repaymentRows(other.tripId, g)).extracting(RepaymentRow::deleted).containsOnly(false);
    }

    @Test
    void aNonMemberOrACancellerWhoLeftGets404ForAGroup() throws Exception {
        Scene s = new Scene();
        String g = uuid();
        groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
        long outsider = user("r-outsider");

        cancelGroup(outsider, s.tripId, g).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        leave(s.aliceMember);
        cancelGroup(s.aliceUser, s.tripId, g).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(repaymentRows(s.tripId, g)).extracting(RepaymentRow::deleted).containsOnly(false);
    }

    @Test
    void withoutIdentityCancellingAGroupIs401() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(post("/trips/" + s.tripId + "/repayments/groups/" + uuid() + "/cancel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aGroupCancelAndAGroupConfirmAtTheSameTimeNeverSplitTheGroup() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            String g = uuid();
            groupRow(s.tripId, g, s.aliceMember, s.meMember, "JPY", 100_000, "pending", false);
            groupRow(s.tripId, g, s.aliceMember, s.meMember, "HKD", 2_000, "pending", false);

            List<Callable<MvcResult>> calls = List.of(
                    () -> confirmGroup(s.meUser, s.tripId, g).andReturn(),
                    () -> cancelGroup(s.aliceUser, s.tripId, g).andReturn());
            List<MvcResult> results = runAll(calls);

            List<RepaymentRow> rows = repaymentRows(s.tripId, g);
            boolean allConfirmed = rows.stream().allMatch(r -> r.confirmed() && !r.deleted());
            boolean allCancelled = rows.stream().allMatch(r -> r.deleted() && !r.confirmed());
            assertThat(allConfirmed || allCancelled).as("一组要么全确认要么全取消：" + rows).isTrue();
            // 先确认：取消得到 409；先取消：确认得到 404（整组没有可确认的行了）
            assertThat(statuses(results)).containsExactly(allConfirmed ? 200 : 404, allConfirmed ? 409 : 200);
        }
    }
}
