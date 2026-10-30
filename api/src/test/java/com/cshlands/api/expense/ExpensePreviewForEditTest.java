package com.cshlands.api.expense;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修改某笔支出前的预览：请求体带 expenseId，这笔里原本就有的付款人和分摊者即使已退出也放行，
 * 新加入的仍然必须在籍；不带 expenseId 时规则和以前完全一样。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class ExpensePreviewForEditTest extends ExpenseTestBase {

    @Test
    void withoutAnExpenseIdALeftMemberIsStillRejected() throws Exception {
        Scene s = new Scene();
        leave(s.bobMember);

        preview(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.bobMember))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_LEFT"));
    }

    @Test
    void withTheExpenseIdMembersAlreadyInTheExpenseAreAllowedEvenAfterLeaving() throws Exception {
        Scene s = new Scene();
        // 付款人 bob，分摊 me、alice、bob；之后 bob 退出
        long id = idOf(create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.bobMember, s.meMember, s.aliceMember, s.bobMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        leave(s.bobMember);

        // 改了金额，让后端重算：付款人 bob 已退出，但他原本就在这笔里，放行；余数仍然给付款人
        preview(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.bobMember, s.meMember, s.aliceMember, s.bobMember)
                .num("expenseId", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splits", hasSize(3)))
                .andExpect(jsonPath("$.splits[?(@.memberId == " + s.bobMember + ")].amountMinor").value(contains(33400)))
                .andExpect(jsonPath("$.splits[?(@.memberId == " + s.bobMember + ")].remainderMinor").value(contains(100)));
        // 不带 expenseId 的同一个请求仍然是 400
        preview(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.bobMember, s.meMember, s.aliceMember, s.bobMember))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_LEFT"));
    }

    @Test
    void aNewlyAddedLeftMemberIsStillRejectedEvenWithTheExpenseId() throws Exception {
        Scene s = new Scene();
        long id = idOf(create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        leave(s.carolMember);

        preview(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.carolMember)
                .num("expenseId", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_LEFT"));
    }

    @Test
    void anExpenseIdTheCallerCannotSeeIsNotFound() throws Exception {
        Scene s = new Scene();
        long mine = idOf(create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        deleteExpense(s.meUser, s.tripId, mine).andExpect(status().isNoContent());
        long alicesPersonal = idOf(create(s.aliceUser, s.tripId, Body.personal(uuid(), 1000, "JPY", s.aliceMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long otherTrip = trip(s.meUser);
        long otherMember = memberId(otherTrip, s.meUser);
        long otherTripExpense = insertExpense(otherTrip, otherMember, otherMember, "equal", 1000);
        Body body = Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember);

        preview(s.meUser, s.tripId, body.num("expenseId", mine)).andExpect(status().isNotFound());          // 已删除
        preview(s.meUser, s.tripId, body.num("expenseId", alicesPersonal)).andExpect(status().isNotFound()); // 别人的个人支出
        preview(s.meUser, s.tripId, body.num("expenseId", otherTripExpense)).andExpect(status().isNotFound()); // 别的行程的
        preview(s.meUser, s.tripId, body.num("expenseId", 999_999_999L)).andExpect(status().isNotFound());    // 不存在
        assertThat(expenseRows(s.tripId)).isEqualTo(2);
    }

    @Test
    void theExpenseIdChangesNothingForAnOrdinaryPreview() throws Exception {
        Scene s = new Scene();
        long id = idOf(create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        Body body = Body.equal(uuid(), 90000, "JPY", s.aliceMember, s.meMember, s.aliceMember);

        String without = preview(s.meUser, s.tripId, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String with = preview(s.meUser, s.tripId, body.num("expenseId", id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(with).isEqualTo(without);
        assertThat(versionOf(id)).isZero(); // 预览只算不写
    }

    @Test
    void anOutsiderIsNotFoundBeforeAnythingElse() throws Exception {
        Scene s = new Scene();
        long id = idOf(create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        preview(user("e-stranger"), s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember).num("expenseId", id))
                .andExpect(status().isNotFound());
    }
}
