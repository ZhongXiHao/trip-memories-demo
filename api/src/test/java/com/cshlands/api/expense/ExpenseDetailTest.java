package com.cshlands.api.expense;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /trips/{tripId}/expenses/{expenseId}：取一笔支出的详情。
 * 看不见的（不存在、已删除、别人的个人支出、别的行程的）一律 404，不泄露它存在过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class ExpenseDetailTest extends ExpenseTestBase {

    @Test
    void returnsTheExpenseObject() throws Exception {
        Scene s = new Scene();
        jdbc.update("UPDATE trip SET start_date = '2026-10-01' WHERE id = ?", s.tripId);
        // 付款人 me，记账人 alice；分摊故意先插 bob、后插 me，看返回是不是按 memberId 排好
        long id = insertExpense(s.tripId, s.meMember, s.aliceMember, "equal", 100000);
        insertSplit(s.tripId, id, s.bobMember, 50000);
        insertSplit(s.tripId, id, s.meMember, 50000);
        jdbc.update("UPDATE expense SET note = '別帳', category = 'food', source = 'agent' WHERE id = ?", id);

        fetch(s.bobUser, s.tripId, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.tripId").value(s.tripId))
                .andExpect(jsonPath("$.title").value("拉麵"))
                .andExpect(jsonPath("$.amountMinor").value(100000))
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.paidByMemberId").value(s.meMember))
                .andExpect(jsonPath("$.createdByMemberId").value(s.aliceMember))
                .andExpect(jsonPath("$.splitMode").value("equal"))
                .andExpect(jsonPath("$.visibility").value("trip"))
                .andExpect(jsonPath("$.splits", hasSize(2)))
                .andExpect(jsonPath("$.splits[0].memberId").value(s.meMember))
                .andExpect(jsonPath("$.splits[0].amountMinor").value(50000))
                .andExpect(jsonPath("$.splits[1].memberId").value(s.bobMember))
                .andExpect(jsonPath("$.splits[0].remainderMinor").doesNotExist())
                .andExpect(jsonPath("$.occurredAt").value("2026-10-03T11:00:00Z"))
                .andExpect(jsonPath("$.occurredTz").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.dayIndex").value(3)) // 东京 10-03 20:00，距 10-01 第 3 天
                .andExpect(jsonPath("$.category").value("food"))
                .andExpect(jsonPath("$.note").value("別帳"))
                .andExpect(jsonPath("$.source").value("agent"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt", endsWith("Z")));
    }

    @Test
    void isExactlyWhatCreateReturned() throws Exception {
        Scene s = new Scene();
        jdbc.update("UPDATE trip SET start_date = '2026-10-01' WHERE id = ?", s.tripId);
        String created = create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember)
                .str("occurredAt", "2026-10-03T11:00:00+09:00").str("note", "別帳"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String fetched = fetch(s.aliceUser, s.tripId, idOf(created))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Object>read(fetched, "$")).isEqualTo(JsonPath.<Object>read(created, "$"));
    }

    @Test
    void dayIndexIsCountedInTheExpensesOwnTimeZone() throws Exception {
        Scene s = new Scene();
        jdbc.update("UPDATE trip SET start_date = '2026-10-01' WHERE id = ?", s.tripId);
        // 同一个时刻 2026-10-03 16:30 UTC：东京已经是 10-04，洛杉矶还是 10-03
        long tokyo = insertExpense(s.tripId, s.meMember, s.meMember, "equal", 1000, "2026-10-03 16:30:00.000", "Asia/Tokyo");
        long losAngeles = insertExpense(s.tripId, s.meMember, s.meMember, "equal", 1000, "2026-10-03 16:30:00.000", "America/Los_Angeles");
        // 开始日期之前也按算出来的数返回，不报错
        long before = insertExpense(s.tripId, s.meMember, s.meMember, "equal", 1000, "2026-09-30 03:00:00.000", "Asia/Tokyo");

        List<String> failures = new ArrayList<>();
        long[][] cases = {{tokyo, 4}, {losAngeles, 3}, {before, 0}};
        for (long[] c : cases) {
            String json = fetch(s.meUser, s.tripId, c[0]).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            Object dayIndex = JsonPath.read(json, "$.dayIndex");
            if (!String.valueOf(dayIndex).equals(String.valueOf(c[1]))) {
                failures.add("支出 " + c[0] + "：期望第 " + c[1] + " 天，实际 " + dayIndex);
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void dayIndexIsNullWhenTheTripHasNoStartDate() throws Exception {
        Scene s = new Scene();
        long id = insertExpense(s.tripId, s.meMember, s.meMember, "equal", 1000);

        fetch(s.meUser, s.tripId, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayIndex").doesNotExist());
    }

    // ---- 可见性 ----

    @Test
    void aPersonalExpenseIsOnlyVisibleToItsOwner() throws Exception {
        Scene s = new Scene();
        long alicesPersonal = insertExpense(s.tripId, s.aliceMember, s.aliceMember, "none", 1000);
        insertSplit(s.tripId, alicesPersonal, s.aliceMember, 1000);

        fetch(s.aliceUser, s.tripId, alicesPersonal)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("private"))
                .andExpect(jsonPath("$.splitMode").value("none"))
                .andExpect(jsonPath("$.splits", hasSize(1)))
                .andExpect(jsonPath("$.splits[0].memberId").value(s.aliceMember));
        // 行程的 Owner 也没有特权；404 而不是 403，不泄露它存在
        fetch(s.meUser, s.tripId, alicesPersonal).andExpect(status().isNotFound());
        fetch(s.bobUser, s.tripId, alicesPersonal).andExpect(status().isNotFound());
    }

    @Test
    void visibilityIsDecidedByMemberIdNotUserId() throws Exception {
        // 创建者的 memberId 是 EXPLICIT_ID；查看者的 userId 恰好也是 EXPLICIT_ID（他的 memberId 是另一个小编号）
        Scene s = new Scene();
        long creatorMember = memberWithId(EXPLICIT_ID, s.tripId, user("e-creator"), "member", "active");
        long viewer = userWithId(EXPLICIT_ID, "e-viewer");
        long viewerMember = member(s.tripId, viewer, "member", "active");
        assertThat(viewerMember).isNotEqualTo(EXPLICIT_ID);

        long personal = insertExpense(s.tripId, creatorMember, creatorMember, "none", 1000);
        insertSplit(s.tripId, personal, creatorMember, 1000);

        // 把 userId 当 memberId 用的话，这里会看见别人的个人支出
        fetch(viewer, s.tripId, personal).andExpect(status().isNotFound());
    }

    @Test
    void deletedUnknownAndOtherTripExpensesAreNotFound() throws Exception {
        Scene s = new Scene();
        long deleted = insertExpense(s.tripId, s.meMember, s.meMember, "equal", 1000);
        jdbc.update("UPDATE expense SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", deleted);
        // 另一个行程里的支出，id 是真的，但不属于这个行程
        long otherTrip = trip(s.meUser);
        long otherTripExpense = insertExpense(otherTrip, memberId(otherTrip, s.meUser), memberId(otherTrip, s.meUser), "equal", 1000);

        fetch(s.meUser, s.tripId, deleted).andExpect(status().isNotFound());
        fetch(s.meUser, s.tripId, otherTripExpense).andExpect(status().isNotFound());
        fetch(s.meUser, s.tripId, 999_999_999L).andExpect(status().isNotFound());
        // 它在自己的行程里还是看得见的
        fetch(s.meUser, otherTrip, otherTripExpense).andExpect(status().isOk());
    }

    // ---- 权限 ----

    @Test
    void outsidersLeftMembersAndDeletedTripsAreNotFound() throws Exception {
        Scene s = new Scene();
        long stranger = user("e-stranger");
        long leaver = user("e-leaver");
        member(s.tripId, leaver, "member", "left");
        long id = insertExpense(s.tripId, s.meMember, s.meMember, "equal", 1000);

        fetch(stranger, s.tripId, id).andExpect(status().isNotFound());
        fetch(leaver, s.tripId, id).andExpect(status().isNotFound());
        fetch(s.meUser, s.tripId + 1000, id).andExpect(status().isNotFound());
        // 已删除的行程
        jdbc.update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", s.tripId);
        fetch(s.meUser, s.tripId, id).andExpect(status().isNotFound());
    }

    @Test
    void requireAuthentication() throws Exception {
        mockMvc.perform(get("/trips/1/expenses/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void theExpenseIdMustBeANumber() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(get("/trips/" + s.tripId + "/expenses/abc").header("X-User-Id", s.meUser))
                .andExpect(status().isBadRequest());
    }
}
