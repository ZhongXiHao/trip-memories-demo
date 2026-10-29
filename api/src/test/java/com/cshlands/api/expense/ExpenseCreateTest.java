package com.cshlands.api.expense;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips/{tripId}/expenses：创建支出。契约见 docs/api-contract.md「支出」和「通用约定」里的幂等一条。
 * 先写测试、后写实现：实现没写完时这些用例是红的，这是预期。
 * 分摊的计算规则在 ExpensePreviewTest 里已经覆盖，这里只抽几个代表，重点是写库、幂等、可见性和并发。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class ExpenseCreateTest extends ExpenseTestBase {

    // ---- 创建成功 ----

    @Test
    void equalExpenseIsCreatedAndRecordsWhoCreatedIt() throws Exception {
        Scene s = new Scene();

        // me 替 alice 记一笔：付款人 alice，记账人 me。1000 円 ÷ 3，余 1 円给付款人 alice
        create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.aliceMember, s.meMember, s.aliceMember, s.bobMember))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.tripId").value(s.tripId))
                .andExpect(jsonPath("$.title").value("拉麵"))
                .andExpect(jsonPath("$.amountMinor").value(100000))
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.paidByMemberId").value(s.aliceMember))
                .andExpect(jsonPath("$.createdByMemberId").value(s.meMember))
                .andExpect(jsonPath("$.splitMode").value("equal"))
                .andExpect(jsonPath("$.visibility").value("trip"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").value(endsWith("Z")))
                .andExpect(jsonPath("$.splits", hasSize(3)))
                .andExpect(hasSplit(s.meMember, 33300))
                .andExpect(hasSplit(s.aliceMember, 33400))
                .andExpect(hasSplit(s.bobMember, 33300))
                // 支出对象里的分摊只有 memberId 和 amountMinor，余数标注只出现在预览里
                .andExpect(jsonPath("$.splits[*].remainderMinor").isEmpty());

        assertThat(expenseRows(s.tripId)).isEqualTo(1);
        assertThat(splitRows(s.tripId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(amount_minor) FROM expense_split WHERE trip_id = ?",
                Long.class, s.tripId)).isEqualTo(100000L);
    }

    @Test
    void customExpenseKeepsTheGivenAmounts() throws Exception {
        Scene s = new Scene();

        create(s.meUser, s.tripId, Body.custom(uuid(), 100000, "JPY", s.meMember,
                s.aliceMember, 60000, s.bobMember, 40000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.splitMode").value("custom"))
                .andExpect(jsonPath("$.visibility").value("trip"))
                .andExpect(jsonPath("$.splits", hasSize(2)))
                .andExpect(hasSplit(s.aliceMember, 60000))
                .andExpect(hasSplit(s.bobMember, 40000));

        assertThat(splitRows(s.tripId)).isEqualTo(2);
    }

    @Test
    void personalExpenseIsPrivateAndSplitsToThePayer() throws Exception {
        Scene s = new Scene();

        create(s.meUser, s.tripId, Body.personal(uuid(), 120000, "JPY", s.meMember))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.splitMode").value("none"))
                .andExpect(jsonPath("$.visibility").value("private"))
                .andExpect(jsonPath("$.paidByMemberId").value(s.meMember))
                .andExpect(jsonPath("$.createdByMemberId").value(s.meMember))
                .andExpect(jsonPath("$.splits", hasSize(1)))
                .andExpect(hasSplit(s.meMember, 120000));

        assertThat(splitRows(s.tripId)).isEqualTo(1);
    }

    @Test
    void omittedOptionalFieldsGetTheirDefaults() throws Exception {
        Scene s = new Scene();
        Instant before = Instant.now();

        String json = create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occurredTz").value("Asia/Tokyo")) // trip 的时区
                .andExpect(jsonPath("$.category").value("other"))
                .andExpect(jsonPath("$.source").value("form"))
                .andExpect(jsonPath("$.note").doesNotExist())
                .andExpect(jsonPath("$.dayIndex").doesNotExist()) // trip 没有开始日期
                .andReturn().getResponse().getContentAsString();

        // 没传 occurredAt：默认是"现在"
        Instant occurredAt = Instant.parse(JsonPath.read(json, "$.occurredAt"));
        assertThat(occurredAt).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    void optionalFieldsAreEchoedBack() throws Exception {
        Scene s = new Scene();

        create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember)
                .str("occurredAt", "2026-10-03T11:00:00Z")
                .str("occurredTz", "Europe/Paris")
                .str("category", "food")
                .str("note", "ラーメン")
                .str("source", "agent"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occurredAt").value("2026-10-03T11:00:00Z"))
                .andExpect(jsonPath("$.occurredTz").value("Europe/Paris"))
                .andExpect(jsonPath("$.category").value("food"))
                .andExpect(jsonPath("$.note").value("ラーメン"))
                .andExpect(jsonPath("$.source").value("agent"));
    }

    @Test
    void occurredAtWithAnOffsetIsStoredAsUtc() throws Exception {
        Scene s = new Scene();

        // 2026-10-03 20:00 +09:00 就是 11:00Z
        create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember)
                .str("occurredAt", "2026-10-03T20:00:00+09:00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occurredAt").value("2026-10-03T11:00:00Z"));

        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(occurred_at, '%Y-%m-%d %H:%i:%s') FROM expense WHERE trip_id = ?",
                String.class, s.tripId)).isEqualTo("2026-10-03 11:00:00");
    }

    @Test
    void dayIndexIsCountedInTheExpensesOwnTimeZone() throws Exception {
        long me = user("e-me");
        long tripId = tripStartingOn(me, "2026-10-01");
        long meMember = memberId(tripId, me);
        // {occurredAt, occurredTz, 期望的 dayIndex}
        String[][] cases = {
                {"2026-10-03T16:00:00Z", "Asia/Tokyo", "4"},       // 东京已经是 10 月 4 日
                {"2026-10-01T14:59:00Z", "Asia/Tokyo", "1"},       // 东京 10 月 1 日 23:59
                {"2026-10-01T15:00:00Z", "Asia/Tokyo", "2"},       // 东京 10 月 2 日 00:00
                {"2026-10-02T02:00:00Z", "America/New_York", "1"}, // 纽约 10 月 1 日 22:00，UTC 日期已是 2 日
                {"2026-09-30T00:00:00Z", "Asia/Tokyo", "0"},       // 在开始日期之前：按计算结果返回，不报错
        };

        List<String> failures = new ArrayList<>();
        for (String[] c : cases) {
            String json = create(me, tripId, Body.personal(uuid(), 1000, "USD", meMember)
                    .str("occurredAt", c[0]).str("occurredTz", c[1]))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            Object dayIndex = JsonPath.read(json, "$.dayIndex");
            if (!String.valueOf(dayIndex).equals(c[2])) {
                failures.add(c[0] + " @ " + c[1] + "：期望第 " + c[2] + " 天，实际 " + dayIndex);
            }
        }
        assertThat(failures).isEmpty();
    }

    // ---- 幂等 ----

    @Test
    void replayOfTheSameRequestReturnsTheExistingExpense() throws Exception {
        Scene s = new Scene();
        String u = uuid();

        String first = create(s.meUser, s.tripId, equalBody(s, u))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = create(s.meUser, s.tripId, equalBody(s, u))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(idOf(second)).isEqualTo(idOf(first));
        for (String path : List.of("$.version", "$.occurredAt", "$.createdAt", "$.amountMinor")) {
            assertThat((Object) JsonPath.read(second, path)).as(path).isEqualTo(JsonPath.read(first, path));
        }
        assertThat(expenseRows(s.tripId)).isEqualTo(1);
        assertThat(splitRows(s.tripId)).isEqualTo(3);
    }

    @Test
    void replayWithDifferentContentIsAConflict() throws Exception {
        Scene s = new Scene();
        String u = uuid();
        create(s.meUser, s.tripId, equalBody(s, u)).andExpect(status().isCreated());

        List<Body> different = List.of(
                equalBody(s, u).num("amountMinor", 200000),
                equalBody(s, u).str("title", "別の店"),
                equalBody(s, u).str("currency", "USD"),
                Body.equal(u, 100000, "JPY", s.aliceMember, s.meMember, s.aliceMember, s.bobMember), // 换了付款人
                Body.equal(u, 100000, "JPY", s.meMember, s.meMember, s.aliceMember),                  // 少了一个人
                Body.custom(u, 100000, "JPY", s.meMember, s.meMember, 50000, s.aliceMember, 30000, s.bobMember, 20000) // 换了分摊方式
        );
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < different.size(); i++) {
            MvcResult result = create(s.meUser, s.tripId, different.get(i)).andReturn();
            if (result.getResponse().getStatus() != 409
                    || !"IDEMPOTENCY_KEY_REUSED".equals(codeOf(result.getResponse().getContentAsString()))) {
                failures.add("第 " + i + " 种：状态 " + result.getResponse().getStatus());
            }
        }
        assertThat(failures).isEmpty();

        // 原来那笔没被改
        assertThat(expenseRows(s.tripId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM expense WHERE trip_id = ?", Long.class, s.tripId))
                .isEqualTo(100000L);
    }

    @Test
    void omittedOptionalFieldsAreNotComparedOnReplay() throws Exception {
        Scene s = new Scene();
        String u = uuid();
        create(s.meUser, s.tripId, equalBody(s, u)
                .str("occurredAt", "2026-10-03T11:00:00Z").str("category", "food").str("note", "メモ"))
                .andExpect(status().isCreated());

        // 重试时没带这三个字段：不参与比较，仍是同一个请求
        create(s.meUser, s.tripId, equalBody(s, u)).andExpect(status().isOk());
        // 带了而且一样：也是同一个请求
        create(s.meUser, s.tripId, equalBody(s, u)
                .str("occurredAt", "2026-10-03T11:00:00Z").str("category", "food").str("note", "メモ"))
                .andExpect(status().isOk());
        // 带了但不一样：内容不一致
        for (Body changed : List.of(
                equalBody(s, u).str("occurredAt", "2026-10-04T11:00:00Z"),
                equalBody(s, u).str("category", "lodging"),
                equalBody(s, u).str("note", "別のメモ"))) {
            create(s.meUser, s.tripId, changed)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        }
        assertThat(expenseRows(s.tripId)).isEqualTo(1);
    }

    @Test
    void replayWithoutOccurredAtIsNotTrippedUpByTheDefaultClock() throws Exception {
        Scene s = new Scene();
        String u = uuid();

        // 两次都没传 occurredAt：默认值各取"现在"，两次不一样，但不能因此判成内容不一致
        String first = create(s.meUser, s.tripId, equalBody(s, u))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Thread.sleep(20);
        String second = create(s.meUser, s.tripId, equalBody(s, u))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // 返回的是已有那笔，occurredAt 还是第一次的
        assertThat((Object) JsonPath.read(second, "$.occurredAt")).isEqualTo(JsonPath.read(first, "$.occurredAt"));
    }

    @Test
    void replayIsStillAnswerEvenAfterAMemberLeft() throws Exception {
        Scene s = new Scene();
        String u1 = uuid();
        String u2 = uuid();
        String first = create(s.meUser, s.tripId, equalBody(s, u1)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // bob 付款，bob 在名单里
        Body bobPays = Body.equal(u2, 100000, "JPY", s.bobMember, s.meMember, s.bobMember);
        String second = create(s.meUser, s.tripId, bobPays).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // 之后成员退出了：重试同一个请求，仍然是 200 和原来那笔，不是 MEMBER_LEFT
        leave(s.aliceMember);
        leave(s.bobMember);
        String replay1 = create(s.meUser, s.tripId, equalBody(s, u1)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replay2 = create(s.meUser, s.tripId, bobPays).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(idOf(replay1)).isEqualTo(idOf(first));
        assertThat(idOf(replay2)).isEqualTo(idOf(second));
        assertThat(expenseRows(s.tripId)).isEqualTo(2);
    }

    @Test
    void replayOfADeletedExpenseIsAConflictAndDoesNotBringItBack() throws Exception {
        Scene s = new Scene();
        String u = uuid();
        create(s.meUser, s.tripId, equalBody(s, u)).andExpect(status().isCreated());
        jdbc.update("UPDATE expense SET deleted_at = CURRENT_TIMESTAMP(3) WHERE trip_id = ?", s.tripId);

        create(s.meUser, s.tripId, equalBody(s, u))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // 没有新建，也没有"复活"
        assertThat(expenseRows(s.tripId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE trip_id = ? AND deleted_at IS NULL",
                Integer.class, s.tripId)).isZero();
    }

    @Test
    void anotherMembersPersonalExpenseIsAConflictAndLeaksNothing() throws Exception {
        Scene s = new Scene();
        String u = uuid();
        create(s.aliceUser, s.tripId, Body.personal(u, 50000, "JPY", s.aliceMember).str("title", "秘密のプレゼント"))
                .andExpect(status().isCreated());

        // me 拿同一个 clientUuid 提交：看不到 alice 的个人支出，既不能返回它，也不能透露它的内容
        String body = create(s.meUser, s.tripId, Body.personal(u, 1000, "JPY", s.meMember))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("秘密のプレゼント").doesNotContain("50000");

        // alice 自己重放，仍然是 200
        create(s.aliceUser, s.tripId, Body.personal(u, 50000, "JPY", s.aliceMember).str("title", "秘密のプレゼント"))
                .andExpect(status().isOk());
        assertThat(expenseRows(s.tripId)).isEqualTo(1);
    }

    @Test
    void personalExpenseOwnershipIsDecidedByMemberIdNotUserId() throws Exception {
        long owner = user("e-owner");
        long tripId = trip(owner);
        // bystander 的 userId 恰好等于 target 的 memberId（EXPLICIT_ID）
        long bystander = userWithId(EXPLICIT_ID, "e-bystander");
        long bystanderMember = member(tripId, bystander, "member", "active");
        long target = user("e-target");
        jdbc.update("INSERT INTO trip_member (id, trip_id, user_id, role, status) VALUES (?, ?, ?, 'member', 'active')",
                EXPLICIT_ID, tripId, target);
        assertThat(bystanderMember).isNotEqualTo(EXPLICIT_ID);
        String u = uuid();

        create(target, tripId, Body.personal(u, 1000, "USD", EXPLICIT_ID)).andExpect(status().isCreated());

        // bystander 不是记账人。如果实现把 userId 当 memberId 比，会误以为这笔是他自己的
        create(bystander, tripId, Body.personal(u, 1000, "USD", bystanderMember))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        // target 自己重放仍是 200
        create(target, tripId, Body.personal(u, 1000, "USD", EXPLICIT_ID)).andExpect(status().isOk());
    }

    @Test
    void sameClientUuidInAnotherTripIsAnotherExpense() throws Exception {
        Scene a = new Scene();
        Scene b = new Scene();
        String u = uuid();

        create(a.meUser, a.tripId, equalBody(a, u)).andExpect(status().isCreated());
        create(b.meUser, b.tripId, equalBody(b, u)).andExpect(status().isCreated());

        assertThat(expenseRows(a.tripId)).isEqualTo(1);
        assertThat(expenseRows(b.tripId)).isEqualTo(1);
    }

    @Test
    void splitsInADifferentOrderAreTheSameRequest() throws Exception {
        Scene s = new Scene();
        String u = uuid();

        // 付款人 me 在名单里，余数给 me，和名单顺序无关
        String first = create(s.meUser, s.tripId, Body.equal(u, 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = create(s.meUser, s.tripId, Body.equal(u, 100000, "JPY", s.meMember, s.bobMember, s.meMember, s.aliceMember))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(idOf(second)).isEqualTo(idOf(first));
    }

    @Test
    void splitsInADifferentOrderAreAConflictWhenThePayerIsNotListedAndThereIsARemainder() throws Exception {
        Scene s = new Scene();
        String u = uuid();

        // 付款人 me 不在名单里：100000 分给 3 人，余数 100 给名单第一位（alice），所以顺序决定了余数落在谁身上
        Body original = Body.equal(u, 100000, "JPY", s.meMember, s.aliceMember, s.bobMember, s.carolMember);
        create(s.meUser, s.tripId, original).andExpect(status().isCreated());

        // 成员、总额、付款人都一样，只是换了顺序：算出来的分摊不同（余数会给 bob），算内容不一致
        create(s.meUser, s.tripId,
                Body.equal(u, 100000, "JPY", s.meMember, s.bobMember, s.aliceMember, s.carolMember))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // 原来的顺序重放，仍然是同一笔
        create(s.meUser, s.tripId, original).andExpect(status().isOk());

        // 库里还是第一次的结果：alice 拿到余数，没被第二次请求改动
        assertThat(expenseRows(s.tripId)).isEqualTo(1);
        assertThat(splitRows(s.tripId)).isEqualTo(3);
        assertThat(splitOf(s.tripId, s.aliceMember)).isEqualTo(33400L);
        assertThat(splitOf(s.tripId, s.bobMember)).isEqualTo(33300L);
        assertThat(splitOf(s.tripId, s.carolMember)).isEqualTo(33300L);
    }

    @Test
    void aRejectedRequestDoesNotUseUpTheClientUuid() throws Exception {
        Scene s = new Scene();
        String u = uuid();

        // 第一次校验失败（币别不存在），没有写任何东西
        create(s.meUser, s.tripId, Body.equal(u, 100000, "XXX", s.meMember, s.meMember, s.aliceMember))
                .andExpect(status().isBadRequest());
        assertThat(expenseRows(s.tripId)).isZero();

        // 改正后用同一个 clientUuid 提交：是首次创建，201
        create(s.meUser, s.tripId, Body.equal(u, 100000, "JPY", s.meMember, s.meMember, s.aliceMember))
                .andExpect(status().isCreated());
        assertThat(expenseRows(s.tripId)).isEqualTo(1);
    }

    // ---- 校验 ----

    @Test
    void clientUuidIsRequiredAndMustBeAUuid() throws Exception {
        Scene s = new Scene();
        List<Body> bad = List.of(
                equalBody(s, uuid()).without("clientUuid"),
                equalBody(s, "").str("clientUuid", ""),
                equalBody(s, "not-a-uuid"),
                equalBody(s, "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")); // 36 位，但不是 UUID

        for (Body body : bad) {
            create(s.meUser, s.tripId, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fields[0].field").value("clientUuid"));
        }
        assertThat(expenseRows(s.tripId)).isZero();
    }

    @Test
    void titleIsRequired() throws Exception {
        Scene s = new Scene();

        for (Body body : List.of(equalBody(s, uuid()).without("title"),
                equalBody(s, uuid()).str("title", ""),
                equalBody(s, uuid()).str("title", "   "))) {
            create(s.meUser, s.tripId, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fields[0].field").value("title"));
        }
        assertThat(expenseRows(s.tripId)).isZero();
    }

    @Test
    void occurredTzMustBeAKnownTimeZone() throws Exception {
        Scene s = new Scene();

        for (String bad : new String[]{"Mars/Olympus", "Tokyo"}) {
            create(s.meUser, s.tripId, equalBody(s, uuid()).str("occurredTz", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fields[0].field").value("occurredTz"));
        }
        assertThat(expenseRows(s.tripId)).isZero();
    }

    @Test
    void occurredAtMustBeAnIsoTimestamp() throws Exception {
        Scene s = new Scene();

        for (String bad : new String[]{"yesterday", "2026-10-03", "2026/10/03 11:00"}) {
            create(s.meUser, s.tripId, equalBody(s, uuid()).str("occurredAt", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fields[0].field").value("occurredAt"));
        }
        assertThat(expenseRows(s.tripId)).isZero();
    }

    @Test
    void theSameRulesAsPreviewApplyAndNothingIsWritten() throws Exception {
        Scene s = new Scene();
        long leftMember = member(s.tripId, user("e-leaver"), "member", "left");
        Scene other = new Scene();
        // {请求, 期望的错误码}
        List<Object[]> cases = List.of(
                new Object[]{Body.equal(uuid(), 100000, "XXX", s.meMember, s.meMember, s.aliceMember), "VALIDATION_FAILED"},
                new Object[]{Body.equal(uuid(), 125050, "JPY", s.meMember, s.meMember, s.aliceMember), "AMOUNT_NOT_ROUNDABLE"},
                new Object[]{Body.equal(uuid(), 0, "JPY", s.meMember, s.meMember, s.aliceMember), "VALIDATION_FAILED"},
                new Object[]{Body.equal(uuid(), 100000, "JPY", s.meMember).without("splits"), "VALIDATION_FAILED"},
                new Object[]{Body.equal(uuid(), 100000, "JPY", s.meMember, s.aliceMember, s.aliceMember), "VALIDATION_FAILED"},
                new Object[]{Body.custom(uuid(), 100000, "JPY", s.meMember, s.meMember, 60000, s.aliceMember, 30000), "SPLIT_SUM_MISMATCH"},
                new Object[]{Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, leftMember), "MEMBER_LEFT"},
                new Object[]{Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, other.meMember), "MEMBER_NOT_IN_TRIP"},
                new Object[]{Body.personal(uuid(), 1000, "USD", s.aliceMember), "VALIDATION_FAILED"} // 个人支出的付款人必须是自己
        );

        List<String> failures = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            MvcResult result = create(s.meUser, s.tripId, (Body) cases.get(i)[0]).andReturn();
            String code = result.getResponse().getStatus() == 400 ? codeOf(result.getResponse().getContentAsString()) : "状态 " + result.getResponse().getStatus();
            if (!cases.get(i)[1].equals(code)) {
                failures.add("第 " + i + " 种：期望 " + cases.get(i)[1] + "，实际 " + code);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(expenseRows(s.tripId)).isZero();
        assertThat(splitRows(s.tripId)).isZero();
    }

    // ---- 权限 ----

    @Test
    void requireAuthentication() throws Exception {
        mockMvc.perform(post("/trips/1/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Body.personal(uuid(), 1000, "USD", 1).json()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void outsidersLeftMembersAndDeletedTripsAreNotFound() throws Exception {
        Scene s = new Scene();
        long stranger = user("e-stranger");
        long leaver = user("e-leaver");
        member(s.tripId, leaver, "member", "left");
        String u = uuid();
        create(s.meUser, s.tripId, equalBody(s, u)).andExpect(status().isCreated());

        // 非成员：404。哪怕带的是已经存在的 clientUuid（不能借此探测），或者请求本身有错
        create(stranger, s.tripId, equalBody(s, u)).andExpect(status().isNotFound());
        create(stranger, s.tripId, equalBody(s, uuid())).andExpect(status().isNotFound());
        create(stranger, s.tripId, Body.equal(uuid(), 100000, "XXX", s.meMember, s.meMember)).andExpect(status().isNotFound());
        // 已退出的成员
        create(leaver, s.tripId, equalBody(s, uuid())).andExpect(status().isNotFound());
        // 不存在的行程
        create(s.meUser, s.tripId + 1000, equalBody(s, uuid())).andExpect(status().isNotFound());
        // 已删除的行程
        jdbc.update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", s.tripId);
        create(s.meUser, s.tripId, equalBody(s, uuid())).andExpect(status().isNotFound());
        create(s.meUser, s.tripId, equalBody(s, u)).andExpect(status().isNotFound());

        assertThat(expenseRows(s.tripId)).isEqualTo(1);
    }

    @Test
    void creatorAndPayerAreMemberIdsNotUserIds() throws Exception {
        long me = userWithId(EXPLICIT_ID, "e-explicit"); // userId 是大编号，memberId 是自增的小编号
        long tripId = trip(me);
        long meMember = memberId(tripId, me);
        assertThat(meMember).isNotEqualTo(me);

        create(me, tripId, Body.personal(uuid(), 1000, "USD", meMember))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdByMemberId").value(meMember))
                .andExpect(jsonPath("$.paidByMemberId").value(meMember));
        // 把 userId 当 memberId 传：必须被拒绝
        create(me, tripId, Body.personal(uuid(), 1000, "USD", me)).andExpect(status().isBadRequest());
        assertThat(expenseRows(tripId)).isEqualTo(1);
    }

    // ---- 并发：同一个 clientUuid 同时提交，由唯一键 uk_expense_idem 裁决 ----

    @Test
    void twoSimultaneousSubmitsCreateOnceAndTheOtherGetsTheExistingExpense() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            Body body = equalBody(s, uuid());

            List<MvcResult> results = runConcurrently(2, () -> create(s.meUser, s.tripId, body).andReturn());

            assertThat(statuses(results)).containsExactlyInAnyOrder(200, 201);
            assertThat(idOf(results.get(0).getResponse().getContentAsString()))
                    .isEqualTo(idOf(results.get(1).getResponse().getContentAsString()));
            assertThat(expenseRows(s.tripId)).isEqualTo(1);
            assertThat(splitRows(s.tripId)).isEqualTo(3);
        }
    }

    @Test
    void manySimultaneousSubmitsStillCreateOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            Scene s = new Scene();
            Body body = equalBody(s, uuid());

            List<MvcResult> results = runConcurrently(4, () -> create(s.meUser, s.tripId, body).andReturn());

            assertThat(statuses(results)).containsExactlyInAnyOrder(200, 200, 200, 201);
            assertThat(expenseRows(s.tripId)).isEqualTo(1);
        }
    }

    @Test
    void simultaneousSubmitsWithDifferentContentCreateOnceAndConflictOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            String u = uuid();
            Body a = equalBody(s, u);
            Body b = equalBody(s, u).num("amountMinor", 200000);

            List<MvcResult> results = runConcurrently(2, new Callable<>() {
                private int next = 0;

                @Override
                public MvcResult call() throws Exception {
                    Body mine;
                    synchronized (this) {
                        mine = (next++ == 0) ? a : b;
                    }
                    return create(s.meUser, s.tripId, mine).andReturn();
                }
            });

            assertThat(statuses(results)).containsExactlyInAnyOrder(201, 409);
            assertThat(expenseRows(s.tripId)).isEqualTo(1);
        }
    }

    // ---- 工具 ----

    /** 1000 円 ÷ 3 人，付款人 me，分摊给 me、alice、bob */
    private static Body equalBody(Scene s, String clientUuid) {
        return Body.equal(clientUuid, 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember);
    }

    /** 支出对象里 memberId 这一项的分摊金额 */
    private static ResultMatcher hasSplit(long memberId, long amountMinor) {
        return jsonPath("$.splits[?(@.memberId == " + memberId + ")].amountMinor").value(contains((int) amountMinor));
    }

    /** 库里这个成员在该行程唯一一笔支出里的分摊金额 */
    private long splitOf(long tripId, long memberId) {
        return jdbc.queryForObject("SELECT amount_minor FROM expense_split WHERE trip_id = ? AND member_id = ?",
                Long.class, tripId, memberId);
    }

    private static List<Integer> statuses(List<MvcResult> results) {
        return results.stream().map(r -> r.getResponse().getStatus()).toList();
    }

    /** 让 count 个线程在同一时刻开始执行 call，返回各自的结果 */
    private List<MvcResult> runConcurrently(int count, Callable<MvcResult> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

}
