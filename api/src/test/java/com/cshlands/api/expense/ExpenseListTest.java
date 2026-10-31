package com.cshlands.api.expense;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /trips/{tripId}/expenses?date=&memberId=&currency=&category=：支出列表。
 * 只返回当前用户看得见的（共同支出 + 自己的个人支出），没删除的；筛选不能越过可见性。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class ExpenseListTest extends ExpenseTestBase {

    // ---- 基本 ----

    @Test
    void returnsFullExpenseObjectsAndEachOneEqualsItsDetail() throws Exception {
        Scene s = new Scene();
        jdbc.update("UPDATE trip SET start_date = '2026-10-01' WHERE id = ?", s.tripId);
        long id = tripExpense(s, s.meMember, s.aliceMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.bobMember, s.meMember);

        String listed = list(s.bobUser, s.tripId, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].visibility").value("trip"))
                .andExpect(jsonPath("$.items[0].splits", hasSize(2)))
                .andExpect(jsonPath("$.items[0].splits[0].memberId").value(s.meMember)) // 按 memberId 排好
                .andExpect(jsonPath("$.items[0].dayIndex").value(3))
                .andReturn().getResponse().getContentAsString();
        String detail = fetch(s.bobUser, s.tripId, id).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Object>read(listed, "$.items[0]")).isEqualTo(JsonPath.<Object>read(detail, "$"));
    }

    @Test
    void noExpensesIsAnEmptyArray() throws Exception {
        Scene s = new Scene();

        list(s.meUser, s.tripId, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void orderIsOccurredAtDescendingThenIdDescending() throws Exception {
        Scene s = new Scene();
        long older = tripExpense(s, s.meMember, s.meMember, "2026-10-02 10:00:00.000", "Asia/Tokyo", s.meMember);
        long sameTimeFirst = tripExpense(s, s.meMember, s.meMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.meMember);
        long sameTimeLater = tripExpense(s, s.meMember, s.meMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.meMember);
        long newest = tripExpense(s, s.meMember, s.meMember, "2026-10-04 09:00:00.000", "Asia/Tokyo", s.meMember);

        assertThat(listIds(s.meUser, s.tripId, "")).containsExactly(newest, sameTimeLater, sameTimeFirst, older);
    }

    @Test
    void dayIndexUsesEachExpensesOwnTimeZone() throws Exception {
        Scene s = new Scene();
        jdbc.update("UPDATE trip SET start_date = '2026-10-01' WHERE id = ?", s.tripId);
        // 同一个时刻 2026-10-03 16:30 UTC：东京是 10-04（第 4 天），洛杉矶是 10-03（第 3 天）
        long tokyo = tripExpense(s, s.meMember, s.meMember, "2026-10-03 16:30:00.000", "Asia/Tokyo", s.meMember);
        long losAngeles = tripExpense(s, s.meMember, s.meMember, "2026-10-03 16:30:00.000", "America/Los_Angeles", s.meMember);

        String json = list(s.meUser, s.tripId, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(dayIndexOf(json, tokyo)).isEqualTo(4);
        assertThat(dayIndexOf(json, losAngeles)).isEqualTo(3);
    }

    @Test
    void everyItemKeepsItsOwnSplits() throws Exception {
        Scene s = new Scene();
        Map<Long, Long> expectedFirstSplit = new HashMap<>();
        for (int i = 1; i <= 30; i++) {
            long id = insertExpense(s.tripId, s.meMember, s.meMember, "equal", 1000L * i);
            insertSplit(s.tripId, id, s.meMember, 100L * i);
            insertSplit(s.tripId, id, s.aliceMember, 900L * i);
            expectedFirstSplit.put(id, 100L * i);
        }

        String json = list(s.meUser, s.tripId, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<String> failures = new ArrayList<>();
        List<Object> items = JsonPath.read(json, "$.items");
        assertThat(items).hasSize(30);
        for (long id : expectedFirstSplit.keySet()) {
            List<Number> amounts = JsonPath.read(json, "$.items[?(@.id == " + id + ")].splits[0].amountMinor");
            if (amounts.size() != 1 || amounts.get(0).longValue() != expectedFirstSplit.get(id)) {
                failures.add("支出 " + id + "：期望第一项分摊 " + expectedFirstSplit.get(id) + "，实际 " + amounts);
            }
        }
        assertThat(failures).isEmpty();
    }

    // ---- 可见性 ----

    @Test
    void tripExpensesAreVisibleToEveryActiveMember() throws Exception {
        Scene s = new Scene();
        long id = tripExpense(s, s.aliceMember, s.aliceMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.aliceMember);

        assertThat(listIds(s.meUser, s.tripId, "")).containsExactly(id);
        assertThat(listIds(s.aliceUser, s.tripId, "")).containsExactly(id);
        assertThat(listIds(s.bobUser, s.tripId, "")).containsExactly(id);
    }

    @Test
    void aPersonalExpenseIsOnlyListedForItsOwner() throws Exception {
        Scene s = new Scene();
        long trip = tripExpense(s, s.meMember, s.meMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.meMember);
        long alicesPersonal = personalExpense(s, s.aliceMember);

        assertThat(listIds(s.aliceUser, s.tripId, "")).containsExactlyInAnyOrder(trip, alicesPersonal);
        // 行程的 Owner 也没有特权
        assertThat(listIds(s.meUser, s.tripId, "")).containsExactly(trip);
        assertThat(listIds(s.bobUser, s.tripId, "")).containsExactly(trip);
    }

    @Test
    void deletedAndOtherTripExpensesAreNotListed() throws Exception {
        Scene s = new Scene();
        long kept = tripExpense(s, s.meMember, s.meMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.meMember);
        long deleted = tripExpense(s, s.meMember, s.meMember, "2026-10-03 12:00:00.000", "Asia/Tokyo", s.meMember);
        jdbc.update("UPDATE expense SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", deleted);
        long otherTrip = trip(s.meUser);
        insertExpense(otherTrip, memberId(otherTrip, s.meUser), memberId(otherTrip, s.meUser), "equal", 1000);

        assertThat(listIds(s.meUser, s.tripId, "")).containsExactly(kept);
    }

    @Test
    void theMemberFilterDoesNotBypassVisibility() throws Exception {
        Scene s = new Scene();
        long alicesTrip = tripExpense(s, s.aliceMember, s.aliceMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.aliceMember, s.bobMember);
        long alicesPersonal = personalExpense(s, s.aliceMember);
        long mysPersonal = personalExpense(s, s.meMember);

        // me 按 alice 筛：只有共同的那笔，alice 的个人支出看不到
        assertThat(listIds(s.meUser, s.tripId, "memberId=" + s.aliceMember)).containsExactly(alicesTrip);
        // alice 自己按自己筛：两笔都在
        assertThat(listIds(s.aliceUser, s.tripId, "memberId=" + s.aliceMember)).containsExactlyInAnyOrder(alicesTrip, alicesPersonal);
        // me 按自己筛：自己的个人支出在
        assertThat(listIds(s.meUser, s.tripId, "memberId=" + s.meMember)).containsExactly(mysPersonal);
    }

    @Test
    void visibilityAndTheMemberFilterAreDecidedByMemberIdNotUserId() throws Exception {
        // 创建者的 memberId 是 EXPLICIT_ID；查看者的 userId 恰好也是 EXPLICIT_ID（他的 memberId 是另一个小编号）
        Scene s = new Scene();
        long creatorMember = memberWithId(EXPLICIT_ID, s.tripId, user("e-creator"), "member", "active");
        long viewer = userWithId(EXPLICIT_ID, "e-viewer");
        long viewerMember = member(s.tripId, viewer, "member", "active");
        assertThat(viewerMember).isNotEqualTo(EXPLICIT_ID);
        long personal = insertExpense(s.tripId, creatorMember, creatorMember, "none", 1000);
        insertSplit(s.tripId, personal, creatorMember, 1000);

        // 把 userId 当 memberId 用的话，这里会看见别人的个人支出
        assertThat(listIds(viewer, s.tripId, "")).isEmpty();
        assertThat(listIds(viewer, s.tripId, "memberId=" + creatorMember)).isEmpty();
    }

    // ---- 筛选 ----

    @Test
    void theDateFilterUsesEachExpensesOwnTimeZone() throws Exception {
        Scene s = new Scene();
        // 东京（UTC+9）：14:59:59Z 是 10-03 的最后一秒，15:00:00Z 是 10-04 的第一秒
        long tokyoLastSecond = tripExpense(s, s.meMember, s.meMember, "2026-10-03 14:59:59.000", "Asia/Tokyo", s.meMember);
        long tokyoFirstSecond = tripExpense(s, s.meMember, s.meMember, "2026-10-03 15:00:00.000", "Asia/Tokyo", s.meMember);
        // 洛杉矶（10 月是 UTC-7）：06:59:59Z 是 10-03 的最后一秒，07:00:00Z 是 10-04 的第一秒
        long laLastSecond = tripExpense(s, s.meMember, s.meMember, "2026-10-04 06:59:59.000", "America/Los_Angeles", s.meMember);
        long laFirstSecond = tripExpense(s, s.meMember, s.meMember, "2026-10-04 07:00:00.000", "America/Los_Angeles", s.meMember);

        assertThat(listIds(s.meUser, s.tripId, "date=2026-10-03")).containsExactlyInAnyOrder(tokyoLastSecond, laLastSecond);
        assertThat(listIds(s.meUser, s.tripId, "date=2026-10-04")).containsExactlyInAnyOrder(tokyoFirstSecond, laFirstSecond);
        assertThat(listIds(s.meUser, s.tripId, "date=2026-10-05")).isEmpty();
        assertThat(listIds(s.meUser, s.tripId, "date=2026-10-02")).isEmpty();
    }

    @Test
    void anInvalidDateIsValidationFailed() throws Exception {
        Scene s = new Scene();
        tripExpense(s, s.meMember, s.meMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.meMember);

        for (String bad : List.of("2026-13-45", "yesterday", "2026-10-3", "20261003", "2026-10-03T11:00:00Z")) {
            list(s.meUser, s.tripId, "date=" + bad)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fields[0].field").value("date"));
        }
    }

    @Test
    void theMemberFilterMatchesThePayerOrAnySplitMember() throws Exception {
        Scene s = new Scene();
        long aliceIsPayer = tripExpense(s, s.aliceMember, s.meMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.meMember, s.bobMember);
        long aliceIsSplitter = tripExpense(s, s.bobMember, s.bobMember, "2026-10-03 12:00:00.000", "Asia/Tokyo", s.meMember, s.aliceMember);
        tripExpense(s, s.bobMember, s.bobMember, "2026-10-03 13:00:00.000", "Asia/Tokyo", s.meMember, s.bobMember); // 和 alice 无关

        assertThat(listIds(s.meUser, s.tripId, "memberId=" + s.aliceMember)).containsExactlyInAnyOrder(aliceIsPayer, aliceIsSplitter);
        assertThat(listIds(s.meUser, s.tripId, "memberId=" + s.carolMember)).isEmpty();
    }

    @Test
    void theMemberFilterAcceptsLeftMembersAndUnknownMembersGiveAnEmptyList() throws Exception {
        Scene s = new Scene();
        long alices = tripExpense(s, s.aliceMember, s.aliceMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.aliceMember);
        leave(s.aliceMember);

        assertThat(listIds(s.meUser, s.tripId, "memberId=" + s.aliceMember)).containsExactly(alices);
        assertThat(listIds(s.meUser, s.tripId, "memberId=999999999")).isEmpty();
    }

    @Test
    void theCurrencyFilter() throws Exception {
        Scene s = new Scene();
        long yen = tripExpense(s, s.meMember, s.meMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.meMember);
        long dollars = tripExpense(s, s.meMember, s.meMember, "2026-10-03 12:00:00.000", "Asia/Tokyo", s.meMember);
        jdbc.update("UPDATE expense SET currency = 'USD' WHERE id = ?", dollars);

        assertThat(listIds(s.meUser, s.tripId, "currency=USD")).containsExactly(dollars);
        assertThat(listIds(s.meUser, s.tripId, "currency=JPY")).containsExactly(yen);
        assertThat(listIds(s.meUser, s.tripId, "currency=XXX")).isEmpty();
    }

    @Test
    void theCategoryFilterAndAnInvalidCategory() throws Exception {
        Scene s = new Scene();
        long food = tripExpense(s, s.meMember, s.meMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.meMember);
        long other = tripExpense(s, s.meMember, s.meMember, "2026-10-03 12:00:00.000", "Asia/Tokyo", s.meMember);
        jdbc.update("UPDATE expense SET category = 'food' WHERE id = ?", food);

        assertThat(listIds(s.meUser, s.tripId, "category=food")).containsExactly(food);
        assertThat(listIds(s.meUser, s.tripId, "category=other")).containsExactly(other);
        list(s.meUser, s.tripId, "category=bogus")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("category"));
    }

    @Test
    void severalFiltersAreCombinedWithAnd() throws Exception {
        Scene s = new Scene();
        long match = tripExpense(s, s.aliceMember, s.aliceMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.aliceMember);
        tripExpense(s, s.aliceMember, s.aliceMember, "2026-10-05 11:00:00.000", "Asia/Tokyo", s.aliceMember); // 日期不对
        long wrongCurrency = tripExpense(s, s.aliceMember, s.aliceMember, "2026-10-03 12:00:00.000", "Asia/Tokyo", s.aliceMember);
        jdbc.update("UPDATE expense SET currency = 'USD' WHERE id = ?", wrongCurrency); // 币别不对
        tripExpense(s, s.bobMember, s.bobMember, "2026-10-03 13:00:00.000", "Asia/Tokyo", s.bobMember); // 成员不对

        assertThat(listIds(s.meUser, s.tripId, "date=2026-10-03&memberId=" + s.aliceMember + "&currency=JPY"))
                .containsExactly(match);
    }

    // ---- 权限 ----

    @Test
    void outsidersLeftMembersAndDeletedTripsAreNotFound() throws Exception {
        Scene s = new Scene();
        long stranger = user("e-stranger");
        long leaver = user("e-leaver");
        member(s.tripId, leaver, "member", "left");
        tripExpense(s, s.meMember, s.meMember, "2026-10-03 11:00:00.000", "Asia/Tokyo", s.meMember);

        list(stranger, s.tripId, "").andExpect(status().isNotFound());
        list(leaver, s.tripId, "").andExpect(status().isNotFound());
        list(s.meUser, s.tripId + 1000, "").andExpect(status().isNotFound());
        jdbc.update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", s.tripId);
        list(s.meUser, s.tripId, "").andExpect(status().isNotFound());
    }

    @Test
    void requireAuthentication() throws Exception {
        mockMvc.perform(get("/trips/1/expenses")).andExpect(status().isUnauthorized());
    }

    // ---- 工具 ----

    /** 一笔共同支出（equal），给 splitMembers 各插一行分摊，每行 1000 */
    private long tripExpense(Scene s, long paidBy, long createdBy, String occurredAtUtc, String tz, long... splitMembers) {
        long id = insertExpense(s.tripId, paidBy, createdBy, "equal", 1000L * splitMembers.length, occurredAtUtc, tz);
        for (long member : splitMembers) {
            insertSplit(s.tripId, id, member, 1000);
        }
        return id;
    }

    /** 一笔个人支出，付款人 = 记账人 = owner，分摊一行分给自己 */
    private long personalExpense(Scene s, long owner) {
        long id = insertExpense(s.tripId, owner, owner, "none", 1000);
        insertSplit(s.tripId, id, owner, 1000);
        return id;
    }

    private List<Long> listIds(long actingUserId, long tripId, String query) throws Exception {
        String json = list(actingUserId, tripId, query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(json, "$.items[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private int dayIndexOf(String listJson, long expenseId) {
        List<Number> values = JsonPath.read(listJson, "$.items[?(@.id == " + expenseId + ")].dayIndex");
        assertThat(values).hasSize(1);
        return values.get(0).intValue();
    }
}
