package com.cshlands.api.member;

import com.cshlands.api.MySqlTestContainerConfig;
import com.cshlands.api.repayment.RepaymentTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 成员退出（POST /members/me/quit）和被踢走（POST /members/{memberId}/remove）时的 warnings：
 * 仍有未结清的欠款只提醒不拦截，{"code": "UNSETTLED_DEBTS", "message": "...", "debts": [欠款总览里的 items]}。
 * "有哪些欠款"和 GET /debts?memberId=<他> 完全一致（不含 simplify）；没有欠款时 warnings 是空数组（MemberLeaveTest 已覆盖）。
 * 金额都是"最小单位 × 100"：100_000 = JPY 1000。数据用 JDBC 直接造。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class MemberLeaveWarningsTest extends RepaymentTestBase {

    // ---- 退出 ----

    @Test
    void aMemberWhoOwesSomeoneIsWarnedWhenQuitting() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);

        String json = quit(s.aliceUser, s.tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.memberId").value(s.aliceMember))
                .andExpect(jsonPath("$.member.status").value("left"))
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andExpect(jsonPath("$.warnings[0].code").value("UNSETTLED_DEBTS"))
                .andExpect(jsonPath("$.warnings[0].message").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Map<String, Object>>read(json, "$.warnings[0]")).containsOnlyKeys("code", "message", "debts");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.warnings[0].debts[0]"))
                .containsOnlyKeys("fromMemberId", "toMemberId", "currency", "amountMinor", "pendingMinor");
        assertThat(warnedDebts(json)).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 100_000));
    }

    @Test
    void quittingDoesNotMakeTheDebtDisappear() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);

        quit(s.aliceUser, s.tripId).andExpect(status().isOk());

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 100_000));
    }

    @Test
    void aMemberWhoIsOwedMoneyIsWarnedToo() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.aliceMember, "JPY", s.bobMember, 100_000);

        String json = quit(s.aliceUser, s.tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andExpect(jsonPath("$.warnings[0].code").value("UNSETTLED_DEBTS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(warnedDebts(json)).containsExactly(owes(s.bobMember, s.aliceMember, "JPY", 100_000));
    }

    @Test
    void theDebtsAreExactlyWhatTheOverviewShowsForThatMemberInTheSameOrder() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);       // alice 欠 me JPY
        expense(s.tripId, s.aliceMember, "HKD", s.meMember, 2_000);         // me 欠 alice HKD（反方向、另一个币别）
        expense(s.tripId, s.aliceMember, "JPY", s.bobMember, 30_000);       // bob 欠 alice JPY
        expense(s.tripId, s.bobMember, "JPY", s.carolMember, 10_000);       // carol 欠 bob：和 alice 无关
        List<Debt> expected = overview(s.meUser, s.tripId, "memberId=" + s.aliceMember);
        assertThat(expected).hasSize(3);

        String json = quit(s.aliceUser, s.tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(1)))      // 所有欠款放在同一个 warning 里，不是一项一个
                .andReturn().getResponse().getContentAsString();

        assertThat(warnedDebts(json)).isEqualTo(expected);
        assertThat(warnedDebts(json)).noneMatch(d -> d.fromMemberId() == s.carolMember);
    }

    @Test
    void debtsWithMembersWhoAlreadyLeftAreIncluded() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);
        leave(s.bobMember);

        String json = quit(s.aliceUser, s.tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        assertThat(warnedDebts(json)).containsExactly(owes(s.aliceMember, s.bobMember, "JPY", 100_000));
    }

    @Test
    void pendingMinorIsReportedAsInTheOverview() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        String json = quit(s.aliceUser, s.tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        assertThat(warnedDebts(json)).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 300_000, 100_000));
    }

    @Test
    void theDebtsAreNotSimplified() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.bobMember, "JPY", s.aliceMember, 100_000);      // alice 欠 bob
        expense(s.tripId, s.carolMember, "JPY", s.bobMember, 100_000);      // bob 欠 carol

        // 化简会把 bob 的余额抵成 0（只剩 alice 欠 carol）；这里要的是 bob 自己的两笔真实欠款
        String json = quit(s.bobUser, s.tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        assertThat(warnedDebts(json)).containsExactlyInAnyOrder(
                owes(s.aliceMember, s.bobMember, "JPY", 100_000),
                owes(s.bobMember, s.carolMember, "JPY", 100_000));
    }

    // ---- 没有净额欠款就不提醒 ----

    @Test
    void aDebtThatIsFullyRepaidDoesNotWarn() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        confirmed(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        quit(s.aliceUser, s.tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(0)));
    }

    @Test
    void aPendingRepaymentWithoutAnyNetDebtDoesNotWarn() throws Exception {
        Scene s = new Scene();
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000);

        quit(s.aliceUser, s.tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(0)));
    }

    @Test
    void personalAndDeletedExpensesDoNotWarn() throws Exception {
        Scene s = new Scene();
        personalExpense(s.tripId, s.aliceMember, "JPY", 100_000);
        long deleted = expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        softDeleteExpense(deleted);

        quit(s.aliceUser, s.tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(0)));
    }

    // ---- 被踢走 ----

    @Test
    void removingAMemberWhoOwesWarnsTheOwner() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);

        String json = remove(s.meUser, s.tripId, s.aliceMember)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.memberId").value(s.aliceMember))
                .andExpect(jsonPath("$.member.status").value("left"))
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andExpect(jsonPath("$.warnings[0].code").value("UNSETTLED_DEBTS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(warnedDebts(json)).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 100_000));
    }

    @Test
    void removingAMemberWhoAlreadyLeftStillComputesTheWarnings() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        leave(s.aliceMember);

        String json = remove(s.meUser, s.tripId, s.aliceMember)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.status").value("left"))
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        assertThat(warnedDebts(json)).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 100_000));
    }

    @Test
    void removingAMemberWithoutDebtsGivesNoWarnings() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);

        remove(s.meUser, s.tripId, s.bobMember)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(0)));
    }

    // ---- 防错位 ----

    @Test
    void debtsAreLookedUpByMemberIdNotByUserId() throws Exception {
        Scene s = new Scene();
        // payer 的 memberId = EXPLICIT_ID；quitter 的 userId = EXPLICIT_ID，自己的 memberId 是另一个数
        long payerUser = user("r-explicit-member");
        long payerMember = memberWithId(EXPLICIT_ID, s.tripId, payerUser, "member", "active");
        long quitterUser = userWithId(EXPLICIT_ID, "r-explicit-user");
        member(s.tripId, quitterUser, "member", "active");
        expense(s.tripId, s.meMember, "JPY", payerMember, 100_000);

        // quitter 自己没有欠款：按 userId 去查会错把 payer 的欠款当成他的
        quit(quitterUser, s.tripId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(0)));

        // 踢 payer（路径里是 memberId）：提醒的是 payer 的欠款
        String json = remove(s.meUser, s.tripId, EXPLICIT_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        assertThat(warnedDebts(json)).containsExactly(owes(EXPLICIT_ID, s.meMember, "JPY", 100_000));
    }

    // ---- 不改变原有的行为 ----

    @Test
    void errorsAreUnchanged() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        long outsider = user("r-outsider");

        quit(outsider, s.tripId).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        remove(s.aliceUser, s.tripId, s.bobMember).andExpect(status().isForbidden());
        quit(s.meUser, s.tripId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_MUST_TRANSFER_FIRST"));
        // 被拒绝的操作不能让任何人变成已退出
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_member WHERE trip_id = ? AND status = 'left'",
                Integer.class, s.tripId)).isZero();
    }

    // ---- 工具 ----

    private ResultActions quit(long actingUserId, long tripId) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/members/me/quit").header("X-User-Id", actingUserId));
    }

    private ResultActions remove(long actingUserId, long tripId, long memberId) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/members/" + memberId + "/remove")
                .header("X-User-Id", actingUserId));
    }

    /** 响应里第一个 warning 的 debts，按响应里的顺序解析成 Debt 列表 */
    private static List<Debt> warnedDebts(String responseJson) {
        List<Map<String, Object>> items = JsonPath.read(responseJson, "$.warnings[0].debts");
        return items.stream()
                .map(item -> new Debt(
                        ((Number) item.get("fromMemberId")).longValue(),
                        ((Number) item.get("toMemberId")).longValue(),
                        (String) item.get("currency"),
                        ((Number) item.get("amountMinor")).longValue(),
                        ((Number) item.get("pendingMinor")).longValue()))
                .toList();
    }
}
