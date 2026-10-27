package com.cshlands.api.expense;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips/{tripId}/expenses/preview。行程、成员直接用 JDBC 造。MockMvc 不带 context-path，路径没有 /api。
 * 覆盖 equal、custom、none 三种 splitMode。
 * 预期金额都按契约「记账预览」一节手算，写在每个用例的注释里。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class ExpensePreviewTest {

    /** 显式指定的大编号，用来构造"userId 恰好等于另一个编号"的错位场景 */
    private static final long EXPLICIT_ID = 900_000_005L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void deleteData() {
        for (long userId : userIds) {
            jdbc.update("DELETE FROM trip_member WHERE user_id = ?", userId);
        }
        for (long userId : userIds) {
            jdbc.update("DELETE FROM trip WHERE created_by = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    // ---- 造数据 ----

    private long user(String nickname) {
        jdbc.update("INSERT INTO app_user (nickname) VALUES (?)", nickname);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM app_user", Long.class);
        userIds.add(id);
        return id;
    }

    private long userWithId(long id, String nickname) {
        jdbc.update("INSERT INTO app_user (id, nickname) VALUES (?, ?)", id, nickname);
        userIds.add(id);
        return id;
    }

    private long trip(long ownerId) {
        jdbc.update("INSERT INTO trip (name, timezone, display_currency, created_by) "
                + "VALUES ('東京', 'Asia/Tokyo', 'HKD', ?)", ownerId);
        long tripId = jdbc.queryForObject("SELECT MAX(id) FROM trip", Long.class);
        member(tripId, ownerId, "owner", "active");
        return tripId;
    }

    private long member(long tripId, long userId, String role, String status) {
        jdbc.update("INSERT INTO trip_member (trip_id, user_id, role, status) VALUES (?, ?, ?, ?)",
                tripId, userId, role, status);
        return memberId(tripId, userId);
    }

    private long memberId(long tripId, long userId) {
        return jdbc.queryForObject("SELECT id FROM trip_member WHERE trip_id = ? AND user_id = ?",
                Long.class, tripId, userId);
    }

    /** 一个行程，成员按 memberId 升序：me（Owner）、alice、bob、carol，都在籍 */
    private class Scene {
        final long me = user("p-me");
        final long tripId = trip(me);
        final long meMember = memberId(tripId, me);
        final long aliceMember = member(tripId, user("p-alice"), "member", "active");
        final long bobMember = member(tripId, user("p-bob"), "member", "active");
        final long carolMember = member(tripId, user("p-carol"), "member", "active");
    }

    // ---- 请求 ----

    /** equal / none 的请求体；mode 或 splits 传 null 表示不带这个字段 */
    private static String body(long amountMinor, String currency, long paidBy, String mode, String splitsJson) {
        StringBuilder sb = new StringBuilder("{\"title\":\"拉麵\"");
        sb.append(",\"amountMinor\":").append(amountMinor);
        sb.append(",\"currency\":\"").append(currency).append('"');
        sb.append(",\"paidByMemberId\":").append(paidBy);
        if (mode != null) {
            sb.append(",\"splitMode\":\"").append(mode).append('"');
        }
        if (splitsJson != null) {
            sb.append(",\"splits\":").append(splitsJson);
        }
        return sb.append('}').toString();
    }

    private static String splits(long... memberIds) {
        return LongStream.of(memberIds)
                .mapToObj(id -> "{\"memberId\":" + id + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** 参数依次是 memberId1, amount1, memberId2, amount2, ... */
    private static String customSplits(long... memberAndAmount) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < memberAndAmount.length; i += 2) {
            items.add("{\"memberId\":" + memberAndAmount[i] + ",\"amountMinor\":" + memberAndAmount[i + 1] + "}");
        }
        return "[" + String.join(",", items) + "]";
    }

    private ResultActions preview(long actingUserId, long tripId, String body) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/expenses/preview")
                .header("X-User-Id", actingUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions equalPreview(Scene s, long amountMinor, String currency, long paidBy, long... splitMembers)
            throws Exception {
        return preview(s.me, s.tripId, body(amountMinor, currency, paidBy, "equal", splits(splitMembers)));
    }

    private ResultActions customPreview(Scene s, long amountMinor, String currency, long paidBy, long... memberAndAmount)
            throws Exception {
        return preview(s.me, s.tripId, body(amountMinor, currency, paidBy, "custom", customSplits(memberAndAmount)));
    }

    // ---- equal：金额 ----

    @Test
    void equalSplitGivesRemainderToPayerWhoIsInTheList() throws Exception {
        Scene s = new Scene();

        // 1000 円 ÷ 3 = 333 円余 1 円（契约里的例子），余数给付款人 me
        equalPreview(s, 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountMinor").value(100000))
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.warnings", hasSize(0)))
                .andExpect(jsonPath("$.splits", hasSize(3)))
                .andExpect(jsonPath("$.splits[0].memberId").value(s.meMember))
                .andExpect(jsonPath("$.splits[0].amountMinor").value(33400))
                .andExpect(jsonPath("$.splits[0].remainderMinor").value(100))
                .andExpect(jsonPath("$.splits[1].memberId").value(s.aliceMember))
                .andExpect(jsonPath("$.splits[1].amountMinor").value(33300))
                .andExpect(jsonPath("$.splits[1].remainderMinor").doesNotExist())
                .andExpect(jsonPath("$.splits[2].memberId").value(s.bobMember))
                .andExpect(jsonPath("$.splits[2].amountMinor").value(33300))
                .andExpect(jsonPath("$.splits[2].remainderMinor").doesNotExist());
    }

    @Test
    void remainderGoesToPayerEvenWhenPayerIsNotFirstInTheList() throws Exception {
        Scene s = new Scene();

        // 付款人 me 排在中间：余数给 me，不是名单第一位
        equalPreview(s, 100000, "JPY", s.meMember, s.aliceMember, s.meMember, s.bobMember)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splits[0].memberId").value(s.aliceMember))
                .andExpect(jsonPath("$.splits[0].amountMinor").value(33300))
                .andExpect(jsonPath("$.splits[0].remainderMinor").doesNotExist())
                .andExpect(jsonPath("$.splits[1].memberId").value(s.meMember))
                .andExpect(jsonPath("$.splits[1].amountMinor").value(33400))
                .andExpect(jsonPath("$.splits[1].remainderMinor").value(100))
                .andExpect(jsonPath("$.splits[2].amountMinor").value(33300))
                .andExpect(jsonPath("$.splits[2].remainderMinor").doesNotExist());
    }

    @Test
    void remainderGoesToFirstInTheListWhenPayerIsNotInIt() throws Exception {
        Scene s = new Scene();

        // 付款人 me 不在名单里，名单是 [carol, alice, bob]：余数给第一位 carol。
        // carol 的 memberId 最大，用来区分"名单第一位"和"memberId 最小"
        equalPreview(s, 100000, "JPY", s.meMember, s.carolMember, s.aliceMember, s.bobMember)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splits[0].memberId").value(s.carolMember))
                .andExpect(jsonPath("$.splits[0].amountMinor").value(33400))
                .andExpect(jsonPath("$.splits[0].remainderMinor").value(100))
                .andExpect(jsonPath("$.splits[1].memberId").value(s.aliceMember))
                .andExpect(jsonPath("$.splits[1].amountMinor").value(33300))
                .andExpect(jsonPath("$.splits[1].remainderMinor").doesNotExist())
                .andExpect(jsonPath("$.splits[2].amountMinor").value(33300))
                .andExpect(jsonPath("$.splits[2].remainderMinor").doesNotExist());
    }

    @Test
    void exactDivisionHasNoRemainderAnywhere() throws Exception {
        Scene s = new Scene();

        // 900 円 ÷ 3 = 300 円，整除：每项都没有 remainderMinor 字段（不是 0）
        equalPreview(s, 90000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splits[0].amountMinor").value(30000))
                .andExpect(jsonPath("$.splits[1].amountMinor").value(30000))
                .andExpect(jsonPath("$.splits[2].amountMinor").value(30000))
                .andExpect(jsonPath("$.splits[*].remainderMinor").isEmpty());
    }

    @Test
    void currencyWithCentsSplitsDownToTheMinorUnit() throws Exception {
        Scene s = new Scene();

        // 10.00 USD ÷ 3：roundingUnit 为 1，333 + 333 + 334，余 1 给付款人
        equalPreview(s, 1000, "USD", s.meMember, s.meMember, s.aliceMember, s.bobMember)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splits[0].amountMinor").value(334))
                .andExpect(jsonPath("$.splits[0].remainderMinor").value(1))
                .andExpect(jsonPath("$.splits[1].amountMinor").value(333))
                .andExpect(jsonPath("$.splits[2].amountMinor").value(333));
    }

    @Test
    void amountSmallerThanOneUnitPerPersonAllGoesToPayer() throws Exception {
        Scene s = new Scene();

        // 1 円 ÷ 3 人：每人 0，余下的 1 円全部给付款人。表上 amount_minor >= 0，允许 0；
        // 这是在记录当前行为，如果以后决定拒绝或给 warning，改这里和契约
        equalPreview(s, 100, "JPY", s.meMember, s.aliceMember, s.meMember, s.bobMember)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splits[0].amountMinor").value(0))
                .andExpect(jsonPath("$.splits[1].amountMinor").value(100))
                .andExpect(jsonPath("$.splits[1].remainderMinor").value(100))
                .andExpect(jsonPath("$.splits[2].amountMinor").value(0));
    }

    @Test
    void splitsAlwaysSumToTotalAndStayOnTheRoundingUnit() throws Exception {
        Scene s = new Scene();
        long[] everyone = {s.meMember, s.aliceMember, s.bobMember, s.carolMember};
        // {金额, 币别, roundingUnit, 人数}
        Object[][] cases = {
                {100000L, "JPY", 100, 3}, {100000L, "JPY", 100, 4}, {100100L, "JPY", 100, 4},
                {100100L, "JPY", 100, 2}, {12000L, "JPY", 100, 1}, {100L, "JPY", 100, 3},
                {1000L, "USD", 1, 3}, {1L, "USD", 1, 3}, {10001L, "USD", 1, 4}, {10000L, "USD", 1, 4},
                {5000000L, "KRW", 100, 3}
        };

        List<String> failures = new ArrayList<>();
        for (Object[] c : cases) {
            long total = (Long) c[0];
            String currency = (String) c[1];
            int unit = (Integer) c[2];
            int n = (Integer) c[3];
            String label = total + " " + currency + " ÷ " + n;

            String json = equalPreview(s, total, currency, s.meMember, Arrays.copyOf(everyone, n))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<Number> amounts = JsonPath.read(json, "$.splits[*].amountMinor");
            List<Number> remainders = JsonPath.read(json, "$.splits[*].remainderMinor");

            long sum = amounts.stream().mapToLong(Number::longValue).sum();
            if (sum != total) {
                failures.add(label + "：分摊之和 " + sum + " ≠ 总额");
            }
            if (amounts.stream().anyMatch(a -> a.longValue() % unit != 0)) {
                failures.add(label + "：有分摊不是 roundingUnit 的整数倍 " + amounts);
            }
            if (amounts.size() != n) {
                failures.add(label + "：分摊项数 " + amounts.size());
            }
            if (remainders.size() > 1) {
                failures.add(label + "：余数出现在多个人身上 " + remainders);
            }
            if (remainders.stream().anyMatch(r -> r.longValue() == 0)) {
                failures.add(label + "：remainderMinor 出现了 0，应该省略");
            }
        }
        assertThat(failures).isEmpty();
    }

    // ---- none：个人支出 ----

    @Test
    void noneModeGivesOneSplitForThePayerWithTheWholeAmount() throws Exception {
        Scene s = new Scene();

        preview(s.me, s.tripId, body(120000, "JPY", s.meMember, "none", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountMinor").value(120000))
                .andExpect(jsonPath("$.splits", hasSize(1)))
                .andExpect(jsonPath("$.splits[0].memberId").value(s.meMember))
                .andExpect(jsonPath("$.splits[0].amountMinor").value(120000))
                .andExpect(jsonPath("$.splits[0].remainderMinor").doesNotExist());
        // 传空数组和不传一样
        preview(s.me, s.tripId, body(120000, "JPY", s.meMember, "none", "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splits", hasSize(1)));
    }

    @Test
    void noneModeRejectsSplitsAndAnotherPayer() throws Exception {
        Scene s = new Scene();

        preview(s.me, s.tripId, body(120000, "JPY", s.meMember, "none", splits(s.aliceMember)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("splits"));
        // 个人支出的付款人必须是自己（别人代记也不行，见 expense 表的 CHECK）
        preview(s.me, s.tripId, body(120000, "JPY", s.aliceMember, "none", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("paidByMemberId"));
    }

    @Test
    void noneModePayerIsComparedByMemberIdNotUserId() throws Exception {
        long me = userWithId(EXPLICIT_ID, "p-explicit"); // userId 是大编号，memberId 是自增的小编号
        long tripId = trip(me);
        long meMember = memberId(tripId, me);
        assertThat(meMember).isNotEqualTo(me);

        preview(me, tripId, body(120000, "JPY", meMember, "none", null))
                .andExpect(status().isOk());
        // 把 userId 当成 memberId 传进来，必须被拒绝，不能被当成"就是我自己"
        preview(me, tripId, body(120000, "JPY", me, "none", null))
                .andExpect(status().isBadRequest());
    }

    // ---- 校验：币别、金额、模式 ----

    @Test
    void unknownCurrencyIsValidationFailed() throws Exception {
        Scene s = new Scene();

        equalPreview(s, 100000, "XXX", s.meMember, s.meMember, s.aliceMember)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("currency"));
    }

    @Test
    void amountMustBeAMultipleOfTheRoundingUnit() throws Exception {
        Scene s = new Scene();

        // JPY 1250.50
        equalPreview(s, 125050, "JPY", s.meMember, s.meMember, s.aliceMember)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AMOUNT_NOT_ROUNDABLE"));
        equalPreview(s, 125000, "JPY", s.meMember, s.meMember, s.aliceMember)
                .andExpect(status().isOk());
        // 到分的币别，任何整数都可以
        equalPreview(s, 125050, "USD", s.meMember, s.meMember, s.aliceMember)
                .andExpect(status().isOk());
    }

    @Test
    void amountMustBePositive() throws Exception {
        Scene s = new Scene();

        for (long bad : new long[]{0, -100}) {
            equalPreview(s, bad, "JPY", s.meMember, s.meMember, s.aliceMember)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fields[0].field").value("amountMinor"));
        }
    }

    @Test
    void splitModeIsRequired() throws Exception {
        Scene s = new Scene();

        preview(s.me, s.tripId, body(100000, "JPY", s.meMember, null, splits(s.meMember)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("splitMode"));
    }

    @Test
    void equalModeNeedsAtLeastOneSplit() throws Exception {
        Scene s = new Scene();

        for (String missing : new String[]{null, "[]"}) {
            preview(s.me, s.tripId, body(100000, "JPY", s.meMember, "equal", missing))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fields[0].field").value("splits"));
        }
    }

    @Test
    void duplicateMembersInSplitsAreRejected() throws Exception {
        Scene s = new Scene();

        // 不拦的话 equal 会多算一个人，创建时还会撞 uk_split_expense_member 变成 500
        equalPreview(s, 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.aliceMember)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("splits"));
    }

    // ---- 校验：成员 ----

    @Test
    void payerAndSplitMembersMustBelongToTheTrip() throws Exception {
        Scene s = new Scene();
        long otherOwner = user("p-other");
        long foreignMember = memberId(trip(otherOwner), otherOwner);

        // 付款人是别的行程的成员
        equalPreview(s, 100000, "JPY", foreignMember, s.meMember, s.aliceMember)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_IN_TRIP"));
        // 分摊者是别的行程的成员
        equalPreview(s, 100000, "JPY", s.meMember, s.meMember, foreignMember)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_IN_TRIP"));
        // 根本不存在的 memberId
        equalPreview(s, 100000, "JPY", s.meMember, s.meMember, 999_999_999L)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_IN_TRIP"));
    }

    @Test
    void userIdPassedAsMemberIdIsNotAMember() throws Exception {
        long me = userWithId(EXPLICIT_ID, "p-explicit");
        long tripId = trip(me);
        long meMember = memberId(tripId, me);
        long aliceMember = member(tripId, user("p-alice"), "member", "active");

        // 付款人和分摊者传的是 me 的 userId（EXPLICIT_ID），而这个行程里没有 id 是它的成员
        preview(me, tripId, body(100000, "JPY", meMember, "equal", splits(EXPLICIT_ID, aliceMember)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_IN_TRIP"));
    }

    @Test
    void leftMembersCannotPayOrBeSplitWith() throws Exception {
        Scene s = new Scene();
        long leftMember = member(s.tripId, user("p-leaver"), "member", "left");

        equalPreview(s, 100000, "JPY", s.meMember, s.meMember, leftMember)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_LEFT"));
        equalPreview(s, 100000, "JPY", leftMember, s.meMember, s.aliceMember)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_LEFT"));
    }

    @Test
    void leftMemberWhoIsNotInTheSplitsDoesNotBlockThePreview() throws Exception {
        Scene s = new Scene();
        member(s.tripId, user("p-leaver"), "member", "left");

        // 行程里有人退出过，但这笔支出和他无关：equal、custom、none 都应该照常成功
        equalPreview(s, 100000, "JPY", s.meMember, s.meMember, s.aliceMember).andExpect(status().isOk());
        customPreview(s, 100000, "JPY", s.meMember, s.meMember, 60000, s.aliceMember, 40000).andExpect(status().isOk());
        preview(s.me, s.tripId, body(100000, "JPY", s.meMember, "none", null)).andExpect(status().isOk());
    }

    // ---- custom ----

    @Test
    void customSplitsAreReturnedAsGivenWithoutRemainder() throws Exception {
        Scene s = new Scene();

        // 付款人 me 不在名单里也可以
        customPreview(s, 100000, "JPY", s.meMember, s.aliceMember, 60000, s.bobMember, 40000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountMinor").value(100000))
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.warnings", hasSize(0)))
                .andExpect(jsonPath("$.splits", hasSize(2)))
                .andExpect(jsonPath("$.splits[0].memberId").value(s.aliceMember))
                .andExpect(jsonPath("$.splits[0].amountMinor").value(60000))
                .andExpect(jsonPath("$.splits[1].memberId").value(s.bobMember))
                .andExpect(jsonPath("$.splits[1].amountMinor").value(40000))
                .andExpect(jsonPath("$.splits[*].remainderMinor").isEmpty());
    }

    @Test
    void customAllowsAZeroShare() throws Exception {
        Scene s = new Scene();

        // 表上 amount_minor >= 0，某人这笔不出钱但仍列在名单里是合法的
        customPreview(s, 100000, "JPY", s.meMember, s.aliceMember, 100000, s.bobMember, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splits[1].memberId").value(s.bobMember))
                .andExpect(jsonPath("$.splits[1].amountMinor").value(0));
    }

    @Test
    void customSumMustEqualTheTotal() throws Exception {
        Scene s = new Scene();

        // 少了
        customPreview(s, 100000, "JPY", s.meMember, s.aliceMember, 60000, s.bobMember, 30000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SPLIT_SUM_MISMATCH"));
        // 多了
        customPreview(s, 100000, "JPY", s.meMember, s.aliceMember, 60000, s.bobMember, 50000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SPLIT_SUM_MISMATCH"));
    }

    @Test
    void customSumCannotBeFooledByLongOverflow() throws Exception {
        Scene s = new Scene();

        // 2 * Long.MAX_VALUE + 1002 = 2^64 + 1000，用 long 累加会回绕成 1000，恰好等于总额
        customPreview(s, 1000, "USD", s.meMember,
                s.meMember, Long.MAX_VALUE, s.aliceMember, Long.MAX_VALUE, s.bobMember, 1002)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SPLIT_SUM_MISMATCH"));
    }

    @Test
    void customRejectsNegativeMissingAndUnroundedAmounts() throws Exception {
        Scene s = new Scene();

        // 负数：之和等于总额也不行
        customPreview(s, 100000, "JPY", s.meMember, s.aliceMember, 150000, s.bobMember, -50000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("splits"));
        // 漏传金额
        preview(s.me, s.tripId, body(100000, "JPY", s.meMember, "custom", splits(s.aliceMember, s.bobMember)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("splits"));
        // 每一项也要是 roundingUnit 的整数倍：JPY 500.50 + 499.50
        customPreview(s, 100000, "JPY", s.meMember, s.aliceMember, 50050, s.bobMember, 49950)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AMOUNT_NOT_ROUNDABLE"));
    }

    @Test
    void customNeedsAtLeastOneSplit() throws Exception {
        Scene s = new Scene();

        for (String missing : new String[]{null, "[]"}) {
            preview(s.me, s.tripId, body(100000, "JPY", s.meMember, "custom", missing))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fields[0].field").value("splits"));
        }
    }

    @Test
    void customChecksMembersToo() throws Exception {
        Scene s = new Scene();
        long leftMember = member(s.tripId, user("p-leaver"), "member", "left");

        customPreview(s, 100000, "JPY", s.meMember, s.aliceMember, 50000, s.aliceMember, 50000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("splits"));
        customPreview(s, 100000, "JPY", s.meMember, s.aliceMember, 50000, leftMember, 50000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_LEFT"));
        customPreview(s, 100000, "JPY", s.meMember, s.aliceMember, 50000, 999_999_999L, 50000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_IN_TRIP"));
    }

    // ---- 权限、只算不写 ----

    @Test
    void outsidersLeftMembersAndDeletedTripsAreNotFound() throws Exception {
        Scene s = new Scene();
        long stranger = user("p-stranger");
        long leaver = user("p-leaver");
        member(s.tripId, leaver, "member", "left");
        String ok = body(100000, "JPY", s.meMember, "equal", splits(s.meMember, s.aliceMember));
        // 请求本身也是错的（币别不存在）：非成员应该先看到 404，不能借校验错误探测 Trip
        String bad = body(100000, "XXX", s.meMember, "equal", splits(s.meMember, s.aliceMember));

        preview(stranger, s.tripId, ok).andExpect(status().isNotFound());
        preview(stranger, s.tripId, bad).andExpect(status().isNotFound());
        preview(leaver, s.tripId, ok).andExpect(status().isNotFound());
        preview(s.me, s.tripId + 1000, ok).andExpect(status().isNotFound());

        jdbc.update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", s.tripId);
        preview(s.me, s.tripId, ok).andExpect(status().isNotFound());
    }

    @Test
    void anyActiveMemberCanPreviewAnExpenseTheyDidNotPay() throws Exception {
        Scene s = new Scene();
        long alice = jdbc.queryForObject("SELECT user_id FROM trip_member WHERE id = ?", Long.class, s.aliceMember);

        // alice 帮 bob 预览：付款人不是自己也可以（记账人和付款人可以不同）
        preview(alice, s.tripId, body(100000, "JPY", s.bobMember, "equal", splits(s.aliceMember, s.bobMember)))
                .andExpect(status().isOk());
    }

    @Test
    void previewWritesNothing() throws Exception {
        Scene s = new Scene();

        equalPreview(s, 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember).andExpect(status().isOk());
        preview(s.me, s.tripId, body(120000, "JPY", s.meMember, "none", null)).andExpect(status().isOk());

        Integer expenses = jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE trip_id = ?", Integer.class, s.tripId);
        Integer splitRows = jdbc.queryForObject("SELECT COUNT(*) FROM expense_split WHERE trip_id = ?", Integer.class, s.tripId);
        assertThat(expenses).isZero();
        assertThat(splitRows).isZero();
    }

    @Test
    void requireAuthentication() throws Exception {
        mockMvc.perform(post("/trips/1/expenses/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(100000, "JPY", 1, "none", null)))
                .andExpect(status().isUnauthorized());
    }

}
