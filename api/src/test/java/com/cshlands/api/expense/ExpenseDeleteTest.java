package com.cshlands.api.expense;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips/{tripId}/expenses/{expenseId}/delete：软删除。
 * 只有付款人或记账人能删；看得见但不是他们 → 403；看不见的（不存在、已删除、别人的个人支出等）→ 404。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class ExpenseDeleteTest extends ExpenseTestBase {

    // ---- 谁能删 ----

    @Test
    void thePayerCanDeleteAndTheRowsAreOnlyMarkedNotRemoved() throws Exception {
        Scene s = new Scene();
        // 付款人 alice，记账人 me
        long id = sharedExpense(s, s.aliceMember, s.meMember);

        deleteExpense(s.aliceUser, s.tripId, id).andExpect(status().isNoContent());

        assertThat(isDeleted(id)).isTrue();
        // 软删除：expense 行和分摊行都还在，算欠款读的是排除了已删除支出的视图
        assertThat(expenseRows(s.tripId)).isEqualTo(1);
        assertThat(splitRows(s.tripId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v_expense_split_active WHERE expense_id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void theCreatorCanDeleteEvenWhenSomeoneElsePaid() throws Exception {
        Scene s = new Scene();
        long id = sharedExpense(s, s.aliceMember, s.meMember);

        deleteExpense(s.meUser, s.tripId, id).andExpect(status().isNoContent());

        assertThat(isDeleted(id)).isTrue();
    }

    @Test
    void anotherMemberCannotDeleteASharedExpense() throws Exception {
        Scene s = new Scene();
        long id = sharedExpense(s, s.aliceMember, s.meMember);

        deleteExpense(s.bobUser, s.tripId, id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(isDeleted(id)).isFalse();
    }

    @Test
    void theTripOwnerHasNoSpecialPrivilege() throws Exception {
        Scene s = new Scene();
        // me 是 Owner，但这笔是 alice 付的、alice 记的
        long id = sharedExpense(s, s.aliceMember, s.aliceMember);

        deleteExpense(s.meUser, s.tripId, id).andExpect(status().isForbidden());

        assertThat(isDeleted(id)).isFalse();
    }

    @Test
    void youCanDeleteYourOwnPersonalExpenseButOthersCannotEvenSeeIt() throws Exception {
        Scene s = new Scene();
        long id = insertExpense(s.tripId, s.aliceMember, s.aliceMember, "none", 1000);
        insertSplit(s.tripId, id, s.aliceMember, 1000);

        // 别人的个人支出：404，不是 403，不泄露它存在
        deleteExpense(s.meUser, s.tripId, id).andExpect(status().isNotFound());
        deleteExpense(s.bobUser, s.tripId, id).andExpect(status().isNotFound());
        assertThat(isDeleted(id)).isFalse();

        deleteExpense(s.aliceUser, s.tripId, id).andExpect(status().isNoContent());
        assertThat(isDeleted(id)).isTrue();
    }

    @Test
    void permissionIsDecidedByMemberIdNotUserId() throws Exception {
        // 付款人和记账人的 memberId 是 EXPLICIT_ID；另一个成员的 userId 恰好也是 EXPLICIT_ID（他的 memberId 是别的小编号）
        Scene s = new Scene();
        long ownerOfExpense = memberWithId(EXPLICIT_ID, s.tripId, user("e-payer"), "member", "active");
        long other = userWithId(EXPLICIT_ID, "e-other");
        member(s.tripId, other, "member", "active");

        long shared = insertExpense(s.tripId, ownerOfExpense, ownerOfExpense, "equal", 1000);
        long personal = insertExpense(s.tripId, ownerOfExpense, ownerOfExpense, "none", 1000);
        insertSplit(s.tripId, personal, ownerOfExpense, 1000);

        // 把 userId 当 memberId 用的话，下面两个都会被删掉
        deleteExpense(other, s.tripId, shared).andExpect(status().isForbidden());
        deleteExpense(other, s.tripId, personal).andExpect(status().isNotFound());
        assertThat(isDeleted(shared)).isFalse();
        assertThat(isDeleted(personal)).isFalse();
    }

    // ---- 删除之后 ----

    @Test
    void afterDeletingTheExpenseIsGoneAndDeletingAgainIsNotFound() throws Exception {
        Scene s = new Scene();
        long id = sharedExpense(s, s.meMember, s.meMember);
        deleteExpense(s.meUser, s.tripId, id).andExpect(status().isNoContent());
        String deletedAt = deletedAt(id);

        fetch(s.meUser, s.tripId, id).andExpect(status().isNotFound());
        deleteExpense(s.meUser, s.tripId, id).andExpect(status().isNotFound());
        deleteExpense(s.aliceUser, s.tripId, id).andExpect(status().isNotFound());
        // 再删一次不会改掉第一次的删除时间
        assertThat(deletedAt(id)).isEqualTo(deletedAt);
    }

    @Test
    void theClientUuidOfADeletedExpenseCannotBeUsedAgain() throws Exception {
        Scene s = new Scene();
        String u = uuid();
        Body body = Body.equal(u, 100000, "JPY", s.meMember, s.meMember, s.aliceMember);
        String created = create(s.meUser, s.tripId, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        deleteExpense(s.meUser, s.tripId, idOf(created)).andExpect(status().isNoContent());

        // 创建的幂等规则：已删除的记录重放是 409，不会把它恢复，也不会新建一笔
        create(s.meUser, s.tripId, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(expenseRows(s.tripId)).isEqualTo(1);
        assertThat(isDeleted(idOf(created))).isTrue();
    }

    // ---- 范围 ----

    @Test
    void anExpenseOfAnotherTripIsNotFoundAndNotTouched() throws Exception {
        Scene s = new Scene();
        long otherTrip = trip(s.meUser);
        long otherMember = memberId(otherTrip, s.meUser);
        long otherTripExpense = insertExpense(otherTrip, otherMember, otherMember, "equal", 1000);

        // 通过这个行程的路径，删不到别的行程里的支出（哪怕调用者是那边的付款人）
        deleteExpense(s.meUser, s.tripId, otherTripExpense).andExpect(status().isNotFound());
        assertThat(isDeleted(otherTripExpense)).isFalse();
        deleteExpense(s.meUser, otherTrip, otherTripExpense).andExpect(status().isNoContent());
    }

    @Test
    void unknownIdsAndOutsidersAreNotFoundAndNothingIsDeleted() throws Exception {
        Scene s = new Scene();
        long stranger = user("e-stranger");
        long leaver = user("e-leaver");
        long leaverMember = member(s.tripId, leaver, "member", "left");
        long id = sharedExpense(s, s.meMember, s.meMember);
        // 已退出的成员即使是这笔的记账人，也不能再删（他连行程都进不去）
        jdbc.update("UPDATE expense SET created_by_member_id = ? WHERE id = ?", leaverMember, id);

        deleteExpense(s.meUser, s.tripId, 999_999_999L).andExpect(status().isNotFound());
        deleteExpense(stranger, s.tripId, id).andExpect(status().isNotFound());
        deleteExpense(leaver, s.tripId, id).andExpect(status().isNotFound());
        deleteExpense(s.meUser, s.tripId + 1000, id).andExpect(status().isNotFound());
        // 已删除的行程
        jdbc.update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", s.tripId);
        deleteExpense(s.meUser, s.tripId, id).andExpect(status().isNotFound());

        assertThat(isDeleted(id)).isFalse();
    }

    @Test
    void requireAuthentication() throws Exception {
        mockMvc.perform(post("/trips/1/expenses/1/delete")).andExpect(status().isUnauthorized());
    }

    @Test
    void theExpenseIdMustBeANumber() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(post("/trips/" + s.tripId + "/expenses/abc/delete").header("X-User-Id", s.meUser))
                .andExpect(status().isBadRequest());
    }

    // ---- 并发 ----

    @Test
    void twoSimultaneousDeletesSucceedOnceAndTheOtherIsNotFound() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            long id = sharedExpense(s, s.meMember, s.meMember);

            List<MvcResult> results = runConcurrently(2, () -> deleteExpense(s.meUser, s.tripId, id).andReturn());

            assertThat(statuses(results)).containsExactlyInAnyOrder(204, 404);
            assertThat(isDeleted(id)).isTrue();
        }
    }

    // ---- 工具 ----

    /** 一笔共同支出，分摊给 me 和 alice 各一半 */
    private long sharedExpense(Scene s, long paidBy, long createdBy) {
        long id = insertExpense(s.tripId, paidBy, createdBy, "equal", 100000);
        insertSplit(s.tripId, id, s.meMember, 50000);
        insertSplit(s.tripId, id, s.aliceMember, 50000);
        return id;
    }

    private boolean isDeleted(long expenseId) {
        return deletedAt(expenseId) != null;
    }

    private String deletedAt(long expenseId) {
        return jdbc.queryForObject("SELECT CAST(deleted_at AS CHAR) FROM expense WHERE id = ?", String.class, expenseId);
    }
}
