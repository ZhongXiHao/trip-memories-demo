package com.cshlands.api.expense;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /trips/{tripId}/expenses/{expenseId}：修改支出，整体替换，带 version 做乐观锁。
 * occurredAt、occurredTz 没传保持原值；其余可选字段没传回到默认。
 * 错误的先后：404（看不见）→ 403（没权限）→ 400（请求有错）→ 409（版本冲突，写库时原子判断）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class ExpenseUpdateTest extends ExpenseTestBase {

    // ---- 成功路径 ----

    @Test
    void thePayerCanReplaceEverythingAndTheVersionGoesUpByOne() throws Exception {
        Scene s = new Scene();
        String created = createEqual(s);
        long id = idOf(created);

        update(s.meUser, s.tripId, id, eq(90000, s.meMember, 0, s.meMember, s.aliceMember)
                .str("title", "新しい店").str("note", "メモ").str("category", "food"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("新しい店"))
                .andExpect(jsonPath("$.amountMinor").value(90000))
                .andExpect(jsonPath("$.note").value("メモ"))
                .andExpect(jsonPath("$.category").value("food"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.splits", hasSize(2)))
                .andExpect(jsonPath("$.splits[0].amountMinor").value(45000))
                .andExpect(jsonPath("$.splits[1].amountMinor").value(45000))
                // 记账人、来源、创建时间不变
                .andExpect(jsonPath("$.createdByMemberId").value(s.meMember))
                .andExpect(jsonPath("$.source").value("form"))
                .andExpect(jsonPath("$.createdAt").value((String) JsonPath.read(created, "$.createdAt")));

        assertThat(versionOf(id)).isEqualTo(1);
        assertThat(splitsOf(id)).containsOnly(Map.entry(s.meMember, 45000L), Map.entry(s.aliceMember, 45000L));
    }

    @Test
    void theCreatorWhoIsNotThePayerCanAlsoUpdate() throws Exception {
        Scene s = new Scene();
        // 记账人 alice，付款人 me
        long id = idOf(create(s.aliceUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        update(s.aliceUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember).str("title", "alice 改的"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 1, s.meMember, s.aliceMember).str("title", "me 改的"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));

        assertThat(titleOf(id)).isEqualTo("me 改的");
    }

    @Test
    void changingTheSplitsReplacesTheOldRowsAndBumpsTheVersionOnlyOnce() throws Exception {
        Scene s = new Scene();
        long id = idOf(createEqual(s)); // me、alice、bob

        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.carolMember))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        assertThat(splitsOf(id)).containsOnlyKeys(s.meMember, s.carolMember);
        assertThat(splitRows(s.tripId)).isEqualTo(2);

        // equal ↔ custom 可以互换
        update(s.meUser, s.tripId, id, cu(100000, s.meMember, 1, s.meMember, 60000, s.carolMember, 40000))
                .andExpect(status().isOk()).andExpect(jsonPath("$.splitMode").value("custom"));
        assertThat(splitsOf(id)).containsOnly(Map.entry(s.meMember, 60000L), Map.entry(s.carolMember, 40000L));
        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 2, s.meMember, s.aliceMember))
                .andExpect(status().isOk()).andExpect(jsonPath("$.splitMode").value("equal"));
        assertThat(versionOf(id)).isEqualTo(3);
    }

    @Test
    void theRemainderOfAnEqualSplitFollowsTheNewPayer() throws Exception {
        Scene s = new Scene();
        long id = idOf(createEqual(s)); // 付款人 me，余数 100 在 me 身上

        update(s.meUser, s.tripId, id, eq(100000, s.aliceMember, 0, s.meMember, s.aliceMember, s.bobMember))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidByMemberId").value(s.aliceMember));

        assertThat(splitsOf(id)).containsOnly(
                Map.entry(s.meMember, 33300L), Map.entry(s.aliceMember, 33400L), Map.entry(s.bobMember, 33300L));
    }

    @Test
    void theResponseIsWhatTheDetailReturnsAfterwards() throws Exception {
        Scene s = new Scene();
        jdbc.update("UPDATE trip SET start_date = '2026-10-01' WHERE id = ?", s.tripId);
        long id = idOf(createEqual(s));

        String updated = update(s.meUser, s.tripId, id, eq(120000, s.meMember, 0, s.meMember, s.aliceMember)
                .str("occurredAt", "2026-10-04T11:00:00+09:00"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String detail = fetch(s.bobUser, s.tripId, id).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Object>read(updated, "$")).isEqualTo(JsonPath.<Object>read(detail, "$"));
    }

    // ---- occurredAt、occurredTz：没传保持原值 ----

    @Test
    void omittedOccurredAtAndOccurredTzKeepTheirValues() throws Exception {
        Scene s = new Scene();
        long id = idOf(create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember)
                .str("occurredAt", "2026-10-03T11:00:00+09:00").str("occurredTz", "America/Los_Angeles"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember).str("title", "改个名字"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurredAt").value("2026-10-03T02:00:00Z"))
                .andExpect(jsonPath("$.occurredTz").value("America/Los_Angeles"));

        assertThat(jdbc.queryForObject("SELECT occurred_tz FROM expense WHERE id = ?", String.class, id))
                .isEqualTo("America/Los_Angeles");
    }

    @Test
    void givingOnlyOccurredAtKeepsTheExpensesOwnTimeZone() throws Exception {
        Scene s = new Scene(); // 行程时区 Asia/Tokyo
        jdbc.update("UPDATE trip SET start_date = '2026-10-01' WHERE id = ?", s.tripId);
        long id = idOf(create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember)
                .str("occurredTz", "America/Los_Angeles"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        // 2026-10-05 01:00 UTC 在洛杉矶是 10-04 18:00，第 4 天；如果错用行程时区（东京 10-05 10:00）就是第 5 天
        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember)
                .str("occurredAt", "2026-10-05T01:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurredTz").value("America/Los_Angeles"))
                .andExpect(jsonPath("$.dayIndex").value(4));
    }

    @Test
    void aChangedTripTimeZoneDoesNotAffectAnUpdateThatOmitsOccurredTz() throws Exception {
        Scene s = new Scene();
        long id = idOf(createEqual(s)); // 没传 occurredTz，取行程当时的时区 Asia/Tokyo
        jdbc.update("UPDATE trip SET timezone = 'Europe/Paris' WHERE id = ?", s.tripId);

        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember).str("title", "改个名字"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurredTz").value("Asia/Tokyo"));
    }

    @Test
    void givenOccurredAtAndOccurredTzReplaceTheOldOnes() throws Exception {
        Scene s = new Scene();
        jdbc.update("UPDATE trip SET start_date = '2026-10-01' WHERE id = ?", s.tripId);
        long id = idOf(createEqual(s));

        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember)
                .str("occurredAt", "2026-10-07T20:00:00+09:00").str("occurredTz", "Asia/Tokyo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurredAt").value("2026-10-07T11:00:00Z"))
                .andExpect(jsonPath("$.occurredTz").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.dayIndex").value(7));
    }

    @Test
    void anInvalidOccurredAtOrOccurredTzIsRejectedAndNothingChanges() throws Exception {
        Scene s = new Scene();
        long id = idOf(createEqual(s));

        List<Object[]> cases = List.of(
                new Object[]{eq(100000, s.meMember, 0, s.meMember, s.aliceMember).str("occurredAt", "2026-10-03"), "occurredAt"},
                new Object[]{eq(100000, s.meMember, 0, s.meMember, s.aliceMember).str("occurredAt", "yesterday"), "occurredAt"},
                new Object[]{eq(100000, s.meMember, 0, s.meMember, s.aliceMember).str("occurredTz", "Tokyo"), "occurredTz"});
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            MvcResult result = update(s.meUser, s.tripId, id, (Body) cases.get(i)[0]).andReturn();
            String response = result.getResponse().getContentAsString();
            if (result.getResponse().getStatus() != 400
                    || !"VALIDATION_FAILED".equals(codeOf(response))
                    || !cases.get(i)[1].equals(JsonPath.read(response, "$.fields[0].field"))) {
                failures.add("第 " + i + " 种：状态 " + result.getResponse().getStatus() + " " + response);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(versionOf(id)).isZero();
    }

    // ---- 其余可选字段：整体替换 ----

    @Test
    void omittedCategoryAndNoteGoBackToTheirDefaults() throws Exception {
        Scene s = new Scene();
        long id = idOf(create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember)
                .str("category", "food").str("note", "メモ"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("other"))
                .andExpect(jsonPath("$.note").doesNotExist());
    }

    @Test
    void clientUuidAndSourceInTheBodyAreIgnored() throws Exception {
        Scene s = new Scene();
        String clientUuid = uuid();
        long id = idOf(create(s.meUser, s.tripId, Body.equal(clientUuid, 100000, "JPY", s.meMember, s.meMember, s.aliceMember)
                .str("source", "agent"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember)
                .str("clientUuid", uuid()).str("source", "form"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("agent"));

        assertThat(jdbc.queryForObject("SELECT client_uuid FROM expense WHERE id = ?", String.class, id)).isEqualTo(clientUuid);
    }

    // ---- 个人支出和共同支出不能互相切换 ----

    @Test
    void cannotSwitchBetweenAPersonalAndATripExpense() throws Exception {
        Scene s = new Scene();
        long trip = idOf(createEqual(s));
        long personal = idOf(create(s.meUser, s.tripId, Body.personal(uuid(), 100000, "JPY", s.meMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        // 共同 → 个人
        update(s.meUser, s.tripId, trip, Body.personal(uuid(), 100000, "JPY", s.meMember).without("clientUuid").num("version", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("splitMode"));
        // 个人 → 共同
        update(s.meUser, s.tripId, personal, eq(100000, s.meMember, 0, s.meMember, s.aliceMember))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("splitMode"));

        assertThat(versionOf(trip)).isZero();
        assertThat(versionOf(personal)).isZero();
        assertThat(splitsOf(personal)).containsOnlyKeys(s.meMember);
    }

    @Test
    void aPersonalExpenseStaysPersonalAndStaysYours() throws Exception {
        Scene s = new Scene();
        long personal = idOf(create(s.meUser, s.tripId, Body.personal(uuid(), 100000, "JPY", s.meMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        update(s.meUser, s.tripId, personal, Body.personal(uuid(), 250000, "JPY", s.meMember).without("clientUuid").num("version", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("private"))
                .andExpect(jsonPath("$.amountMinor").value(250000))
                .andExpect(jsonPath("$.splits", hasSize(1)))
                .andExpect(jsonPath("$.splits[0].memberId").value(s.meMember))
                .andExpect(jsonPath("$.splits[0].amountMinor").value(250000));

        // 付款人必须是自己
        update(s.meUser, s.tripId, personal, Body.personal(uuid(), 250000, "JPY", s.aliceMember).without("clientUuid").num("version", 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(versionOf(personal)).isEqualTo(1);
    }

    // ---- 成员 ----

    @Test
    void newMembersMustBeActiveTripMembers() throws Exception {
        Scene s = new Scene();
        long id = idOf(createEqual(s));
        long outsiderUser = user("e-outsider");
        long outsiderMember = memberId(trip(outsiderUser), outsiderUser); // 别的行程的成员
        leave(s.carolMember);

        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, outsiderMember))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_NOT_IN_TRIP"));
        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.carolMember))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_LEFT"));
        update(s.meUser, s.tripId, id, eq(100000, s.carolMember, 0, s.meMember, s.aliceMember))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_LEFT"));

        assertThat(versionOf(id)).isZero();
        assertThat(splitsOf(id)).containsOnlyKeys(s.meMember, s.aliceMember, s.bobMember);
    }

    @Test
    void membersAlreadyInTheExpenseMayStayEvenAfterTheyLeft() throws Exception {
        Scene s = new Scene();
        // 付款人 bob（也是分摊者之一），记账人 me
        long id = idOf(create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.bobMember, s.meMember, s.aliceMember, s.bobMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        leave(s.bobMember);

        // 原样保留：付款人 bob、分摊者里有 bob，都已退出，仍可以保存
        update(s.meUser, s.tripId, id, eq(100000, s.bobMember, 0, s.meMember, s.aliceMember, s.bobMember).str("title", "改个名字"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        assertThat(splitsOf(id)).containsOnlyKeys(s.meMember, s.aliceMember, s.bobMember);

        // 把 bob 换成另一个已退出的人：不行
        leave(s.carolMember);
        update(s.meUser, s.tripId, id, eq(100000, s.bobMember, 1, s.meMember, s.aliceMember, s.carolMember))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_LEFT"));

        // 把 bob 从这笔里完全拿掉（付款人换成 me，分摊里也没有他）：之后他不再"原本就在这笔里"
        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 1, s.meMember, s.aliceMember))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 2, s.meMember, s.aliceMember, s.bobMember))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_LEFT"));
        assertThat(splitsOf(id)).containsOnlyKeys(s.meMember, s.aliceMember);
    }

    // ---- 校验：与创建相同，失败时什么都不改 ----

    @Test
    void invalidRequestsAreRejectedAndNothingChanges() throws Exception {
        Scene s = new Scene();
        long id = idOf(createEqual(s));
        Map<Long, Long> splitsBefore = splitsOf(id);

        List<Object[]> cases = List.of(
                new Object[]{eq(100000, s.meMember, 0, s.meMember, s.aliceMember).str("currency", "XXX"), "VALIDATION_FAILED"},
                new Object[]{eq(100050, s.meMember, 0, s.meMember, s.aliceMember), "AMOUNT_NOT_ROUNDABLE"},
                new Object[]{cu(100000, s.meMember, 0, s.meMember, 60000, s.aliceMember, 30000), "SPLIT_SUM_MISMATCH"},
                new Object[]{eq(100000, s.meMember, 0, s.meMember, s.meMember), "VALIDATION_FAILED"},          // 重复成员
                new Object[]{eq(100000, s.meMember, 0).raw("splits", "[]"), "VALIDATION_FAILED"},               // equal 没有成员
                new Object[]{eq(100000, s.meMember, 0, s.meMember).without("splits"), "VALIDATION_FAILED"},    // 没有 splits
                new Object[]{cu(100000, s.meMember, 0, s.meMember, 150000, s.aliceMember, -50000), "VALIDATION_FAILED"}, // 负数
                new Object[]{cu(100000, s.meMember, 0).raw("splits", "[{\"memberId\":" + s.meMember + "}]"), "VALIDATION_FAILED"}, // 缺金额
                new Object[]{eq(100000, s.meMember, 0, s.meMember, s.aliceMember).str("title", "   "), "VALIDATION_FAILED"},
                new Object[]{eq(100000, s.meMember, 0, s.meMember, s.aliceMember).without("version"), "VALIDATION_FAILED"},
                new Object[]{eq(100000, s.meMember, 0, s.meMember, s.aliceMember).without("splitMode"), "VALIDATION_FAILED"},
                new Object[]{eq(100000, s.meMember, 0, s.meMember, s.aliceMember).num("amountMinor", 0), "VALIDATION_FAILED"});
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            MvcResult result = update(s.meUser, s.tripId, id, (Body) cases.get(i)[0]).andReturn();
            int httpStatus = result.getResponse().getStatus();
            String code = httpStatus == 400 ? codeOf(result.getResponse().getContentAsString()) : null;
            if (httpStatus != 400 || !cases.get(i)[1].equals(code)) {
                failures.add("第 " + i + " 种：期望 400 " + cases.get(i)[1] + "，实际 " + httpStatus + " " + code);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(versionOf(id)).isZero();
        assertThat(titleOf(id)).isEqualTo("拉麵");
        assertThat(splitsOf(id)).isEqualTo(splitsBefore);
    }

    @Test
    void aMissingVersionNamesTheVersionField() throws Exception {
        Scene s = new Scene();
        long id = idOf(createEqual(s));

        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember).without("version"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("version"));
    }

    // ---- 乐观锁 ----

    @Test
    void aStaleVersionIsAConflictAndChangesNothing() throws Exception {
        Scene s = new Scene();
        long id = idOf(createEqual(s));
        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember).str("title", "第一次"))
                .andExpect(status().isOk());

        // 旧版本、将来的版本，都是 409；同一个版本提交第二次也是
        for (int staleVersion : new int[]{0, 5}) {
            update(s.meUser, s.tripId, id, eq(100000, s.meMember, staleVersion, s.meMember, s.aliceMember).str("title", "不该生效"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        }
        assertThat(titleOf(id)).isEqualTo("第一次");
        assertThat(versionOf(id)).isEqualTo(1);
        // 用对的版本可以继续
        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 1, s.meMember, s.aliceMember).str("title", "第二次"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void changingOnlyTheSplitsStillChecksTheVersion() throws Exception {
        Scene s = new Scene();
        // 付款人 me，记账人 alice：两个人都有权限改这一笔
        long id = idOf(create(s.aliceUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember, s.bobMember).str("title", "别人先改了标题"))
                .andExpect(status().isOk());

        // 另一个有权限的人拿着旧版本只改分摊：必须冲突，不能悄悄覆盖
        update(s.aliceUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.carolMember))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        assertThat(splitsOf(id)).containsOnlyKeys(s.meMember, s.aliceMember, s.bobMember);
    }

    @Test
    void twoSimultaneousUpdatesWithTheSameVersionSucceedOnceAndConflictOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            long id = idOf(createEqual(s));
            AtomicInteger turn = new AtomicInteger();

            List<MvcResult> results = runConcurrently(2, () -> {
                // 一个把分摊改成 me+alice 并起名 A，另一个改成 me+bob 并起名 B
                boolean first = turn.getAndIncrement() == 0;
                Body body = eq(100000, s.meMember, 0, s.meMember, first ? s.aliceMember : s.bobMember)
                        .str("title", first ? "A" : "B");
                return update(s.meUser, s.tripId, id, body).andReturn();
            });

            assertThat(statuses(results)).containsExactlyInAnyOrder(200, 409);
            assertThat(versionOf(id)).isEqualTo(1);
            // 标题和分摊必须来自同一个请求，不能混在一起
            String title = titleOf(id);
            long expectedOther = "A".equals(title) ? s.aliceMember : s.bobMember;
            assertThat(splitsOf(id)).containsOnlyKeys(s.meMember, expectedOther);
        }
    }

    @Test
    void anUpdateAndADeleteAtTheSameTimeNeverBringTheExpenseBack() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            long id = idOf(createEqual(s));
            AtomicInteger turn = new AtomicInteger();

            List<MvcResult> results = runConcurrently(2, () -> turn.getAndIncrement() == 0
                    ? update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember)).andReturn()
                    : deleteExpense(s.meUser, s.tripId, id).andReturn());

            // 要么先改后删（200、204），要么先删后改（改得 404、删得 204）
            List<Integer> sorted = statuses(results).stream().sorted().toList();
            assertThat(List.of(List.of(200, 204), List.of(204, 404))).contains(sorted);
            assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM expense WHERE id = ?", Boolean.class, id)).isTrue();
        }
    }

    // ---- 权限 ----

    @Test
    void neitherThePayerNorTheCreatorGetsForbiddenEvenTheTripOwner() throws Exception {
        Scene s = new Scene();
        // 付款人和记账人都是 alice
        long id = idOf(create(s.aliceUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.aliceMember, s.meMember, s.aliceMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        for (long outsider : new long[]{s.bobUser, s.meUser}) { // me 是 Owner
            update(outsider, s.tripId, id, eq(100000, s.aliceMember, 0, s.meMember, s.aliceMember).str("title", "不该生效"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(titleOf(id)).isEqualTo("拉麵");
        assertThat(versionOf(id)).isZero();
    }

    @Test
    void anotherMembersPersonalExpenseIsNotFoundNotForbidden() throws Exception {
        Scene s = new Scene();
        long alicesPersonal = idOf(create(s.aliceUser, s.tripId, Body.personal(uuid(), 100000, "JPY", s.aliceMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        update(s.meUser, s.tripId, alicesPersonal, Body.personal(uuid(), 100000, "JPY", s.meMember).without("clientUuid").num("version", 0))
                .andExpect(status().isNotFound());
        update(s.bobUser, s.tripId, alicesPersonal, eq(100000, s.bobMember, 0, s.bobMember))
                .andExpect(status().isNotFound());
        assertThat(versionOf(alicesPersonal)).isZero();
    }

    @Test
    void outsidersLeftMembersAndMissingTripsAreNotFound() throws Exception {
        Scene s = new Scene();
        long stranger = user("e-stranger");
        long leaver = user("e-leaver");
        long leaverMember = member(s.tripId, leaver, "member", "left");
        long id = insertExpense(s.tripId, s.meMember, leaverMember, "equal", 100000); // 退出的人是记账人
        insertSplit(s.tripId, id, s.meMember, 100000);
        Body body = eq(100000, s.meMember, 0, s.meMember);

        update(stranger, s.tripId, id, body).andExpect(status().isNotFound());
        update(leaver, s.tripId, id, body).andExpect(status().isNotFound());
        update(s.meUser, s.tripId + 1000, id, body).andExpect(status().isNotFound());
        jdbc.update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", s.tripId);
        update(s.meUser, s.tripId, id, body).andExpect(status().isNotFound());
        assertThat(versionOf(id)).isZero();
    }

    @Test
    void deletedUnknownAndOtherTripExpensesAreNotFound() throws Exception {
        Scene s = new Scene();
        long id = idOf(createEqual(s));
        deleteExpense(s.meUser, s.tripId, id).andExpect(status().isNoContent());
        long otherTrip = trip(s.meUser);
        long otherMember = memberId(otherTrip, s.meUser);
        long otherTripExpense = insertExpense(otherTrip, otherMember, otherMember, "equal", 100000);
        insertSplit(otherTrip, otherTripExpense, otherMember, 100000);

        // 已删除的：用它删除前的版本改，也是 404，不是 409
        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember)).andExpect(status().isNotFound());
        update(s.meUser, s.tripId, otherTripExpense, eq(100000, s.meMember, 0, s.meMember)).andExpect(status().isNotFound());
        update(s.meUser, s.tripId, 999_999_999L, eq(100000, s.meMember, 0, s.meMember)).andExpect(status().isNotFound());
        assertThat(versionOf(otherTripExpense)).isZero();
    }

    @Test
    void requireAuthenticationAndTheExpenseIdMustBeANumber() throws Exception {
        Scene s = new Scene();
        String json = eq(100000, s.meMember, 0, s.meMember).json();

        mockMvc.perform(put("/trips/1/expenses/1").contentType("application/json").content(json))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/trips/" + s.tripId + "/expenses/abc").header("X-User-Id", s.meUser)
                        .contentType("application/json").content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void permissionIsDecidedByMemberIdNotUserId() throws Exception {
        // 付款人和记账人的 memberId 是 EXPLICIT_ID；另一个成员的 userId 恰好也是 EXPLICIT_ID（他的 memberId 是别的小编号）
        Scene s = new Scene();
        long ownerOfExpense = memberWithId(EXPLICIT_ID, s.tripId, user("e-payer"), "member", "active");
        long other = userWithId(EXPLICIT_ID, "e-other");
        member(s.tripId, other, "member", "active");
        long tripExpense = insertExpense(s.tripId, ownerOfExpense, ownerOfExpense, "equal", 100000);
        insertSplit(s.tripId, tripExpense, ownerOfExpense, 100000);
        long personal = insertExpense(s.tripId, ownerOfExpense, ownerOfExpense, "none", 100000);
        insertSplit(s.tripId, personal, ownerOfExpense, 100000);

        // 把 userId 当 memberId 用的话，下面两个都会改成功
        update(other, s.tripId, tripExpense, eq(100000, s.meMember, 0, s.meMember)).andExpect(status().isForbidden());
        update(other, s.tripId, personal, Body.personal(uuid(), 100000, "JPY", s.meMember).without("clientUuid").num("version", 0))
                .andExpect(status().isNotFound());
        assertThat(versionOf(tripExpense)).isZero();
        assertThat(versionOf(personal)).isZero();
    }

    @Test
    void errorsComeInTheDocumentedOrder() throws Exception {
        Scene s = new Scene();
        long id = idOf(createEqual(s));
        update(s.meUser, s.tripId, id, eq(100000, s.meMember, 0, s.meMember, s.aliceMember)).andExpect(status().isOk()); // 现在 version 是 1
        Body invalid = eq(100000, s.meMember, 0, s.meMember, s.aliceMember).str("currency", "XXX");

        // 没权限 + 请求有错：403 在 400 之前
        update(s.bobUser, s.tripId, id, invalid).andExpect(status().isForbidden());
        // 请求有错 + 版本过期：400 在 409 之前
        update(s.meUser, s.tripId, id, invalid).andExpect(status().isBadRequest());
        // 看不见 + 请求有错：404 在 400 之前
        update(user("e-stranger"), s.tripId, id, invalid).andExpect(status().isNotFound());
    }

    // ---- 工具 ----

    /** 1000 円 ÷ 3 人，付款人 me、记账人 me，分摊 me、alice、bob（me 拿余数，33400 / 33300 / 33300）。返回创建响应 */
    private String createEqual(Scene s) throws Exception {
        return create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    /** 修改的请求体：equal，日元，不带 clientUuid，带 version */
    private static Body eq(long amount, long paidBy, long version, long... members) {
        return Body.equal(uuid(), amount, "JPY", paidBy, members).without("clientUuid").num("version", version);
    }

    /** 修改的请求体：custom，参数依次是 memberId1, amount1, memberId2, amount2, ... */
    private static Body cu(long amount, long paidBy, long version, long... memberAndAmount) {
        return Body.custom(uuid(), amount, "JPY", paidBy, memberAndAmount).without("clientUuid").num("version", version);
    }
}
