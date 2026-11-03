package com.cshlands.api.repayment;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips/{tripId}/repayments：创建还款。
 * 默认场景：me 付了款，alice 欠 me；alice（付款方）向 me（收款方）还。金额是"最小单位 × 100"，JPY 的取整单位是 100，HKD 是 1。
 * 欠款、还款都用 JDBC 造，不依赖别的接口（有两条端到端的例外，已标明）。
 * 成员 memberId 升序：me < alice < bob < carol。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class RepaymentCreateTest extends RepaymentTestBase {

    // ---- 小工具 ----

    /** me 付款，alice 欠 me JPY amount */
    private void aliceOwesMe(Scene s, long amountMinor) {
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, amountMinor);
    }

    /** alice 向 me 还 JPY（同币别，一项） */
    private RepaymentBody alicePaysMe(Scene s, String uuid, long amountMinor) {
        return RepaymentBody.of(uuid, s.aliceMember, s.meMember, "JPY", item("JPY", amountMinor));
    }

    /** 请求被拒：状态码和 code 对，而且库里没有多出还款 */
    private void assertRejected(long userId, Scene s, RepaymentBody body, int httpStatus, String code) throws Exception {
        int before = repaymentCount(s.tripId);
        createRepayment(userId, s.tripId, body)
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code));
        assertThat(repaymentCount(s.tripId)).as("失败的请求不能留下还款").isEqualTo(before);
    }

    /** 400 VALIDATION_FAILED，fields 里有这个字段，库里没有多出还款 */
    private void assertInvalid(long userId, Scene s, RepaymentBody body, String field) throws Exception {
        int before = repaymentCount(s.tripId);
        createRepayment(userId, s.tripId, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[*].field", hasItem(field)));
        assertThat(repaymentCount(s.tripId)).isEqualTo(before);
    }

    private void assertExceedsDebt(long userId, Scene s, RepaymentBody body) throws Exception {
        assertRejected(userId, s, body, 400, "REPAYMENT_EXCEEDS_DEBT");
    }

    // ---- 成功路径 ----

    @Test
    void thePayerMarksARepaymentAsPending() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        String u = uuid();

        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, u, 100_000).str("note", "先还一部分"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementGroupId").value(u))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").isNumber())
                .andExpect(jsonPath("$.items[0].settlementGroupId").value(u))
                .andExpect(jsonPath("$.items[0].expenseId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].fromMemberId").value(s.aliceMember))
                .andExpect(jsonPath("$.items[0].toMemberId").value(s.meMember))
                .andExpect(jsonPath("$.items[0].clearedAmountMinor").value(100_000))
                .andExpect(jsonPath("$.items[0].clearedCurrency").value("JPY"))
                .andExpect(jsonPath("$.items[0].paidAmountMinor").value(100_000))
                .andExpect(jsonPath("$.items[0].paidCurrency").value("JPY"))
                .andExpect(jsonPath("$.items[0].status").value("pending"))
                .andExpect(jsonPath("$.items[0].confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].note").value("先还一部分"))
                .andExpect(jsonPath("$.items[0].createdByMemberId").value(s.aliceMember))
                .andExpect(jsonPath("$.items[0].version").value(0))
                .andExpect(jsonPath("$.items[0].createdAt").isString());

        assertThat(repaymentRows(s.tripId)).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("pending");
            assertThat(row.confirmed()).isFalse();
            assertThat(row.deleted()).isFalse();
            assertThat(row.settlementGroupId()).isEqualTo(u);
            assertThat(row.createdByMemberId()).isEqualTo(s.aliceMember);
        });
    }

    @Test
    void theResponseObjectHasExactlyTheContractFields() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        String json = createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys("settlementGroupId", "items");
        // 值是 null 的字段也要在（契约示例里就是 "expenseId": null）
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.items[0]")).containsOnlyKeys(
                "id", "settlementGroupId", "expenseId", "fromMemberId", "toMemberId",
                "clearedAmountMinor", "clearedCurrency", "paidAmountMinor", "paidCurrency",
                "status", "confirmedAt", "note", "createdByMemberId", "version", "createdAt");
    }

    @Test
    void thePayeeRecordingItAsReceivedMakesItConfirmedImmediately() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        createRepayment(s.meUser, s.tripId, alicePaysMe(s, uuid(), 100_000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].status").value("confirmed"))
                .andExpect(jsonPath("$.items[0].confirmedAt").isString())
                .andExpect(jsonPath("$.items[0].createdByMemberId").value(s.meMember))
                .andExpect(jsonPath("$.items[0].fromMemberId").value(s.aliceMember));

        assertThat(repaymentRows(s.tripId)).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("confirmed");
            assertThat(row.confirmed()).isTrue();
            assertThat(row.createdByMemberId()).isEqualTo(s.meMember);
        });
    }

    @Test
    void aMultiCurrencyTransferIsOneGroupWithARowPerCurrencyOrderedByCurrency() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 820_000);
        expense(s.tripId, s.meMember, "HKD", s.aliceMember, 2_000);
        String u = uuid();
        // 一次转账共 HKD 446.40：其中 426.40 抵 JPY 8200，20.00 抵 HKD 20.00（HKD 对 HKD，实付必须等于销帐）
        RepaymentBody body = RepaymentBody.of(u, s.aliceMember, s.meMember, "HKD",
                item("JPY", 820_000, 42_640), item("HKD", 2_000, 2_000));

        String json = createRepayment(s.aliceUser, s.tripId, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementGroupId").value(u))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].clearedCurrency").value("HKD"))
                .andExpect(jsonPath("$.items[1].clearedCurrency").value("JPY"))
                .andExpect(jsonPath("$.items[1].clearedAmountMinor").value(820_000))
                .andExpect(jsonPath("$.items[1].paidAmountMinor").value(42_640))
                .andExpect(jsonPath("$.items[1].paidCurrency").value("HKD"))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(json, "$.items[*].settlementGroupId")).containsOnly(u);
        assertThat(repaymentRows(s.tripId, u)).extracting(RepaymentRow::clearedCurrency).containsExactly("HKD", "JPY");
        assertThat(repaymentRows(s.tripId, u)).extracting(RepaymentRow::paidCurrency).containsOnly("HKD");
    }

    @Test
    void repayingExactlyTheWholeDebtIsAllowed() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 300_000)).andExpect(status().isCreated());
    }

    @Test
    void aPartialRepaymentLeavesTheRestOfTheDebtInPlace() throws Exception {
        // 端到端：依赖欠款总览接口
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        createRepayment(s.meUser, s.tripId, alicePaysMe(s, uuid(), 100_000)).andExpect(status().isCreated());

        assertThat(overview(s.meUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 200_000));
    }

    @Test
    void aPendingRepaymentShowsUpAsPendingMinorInTheOverview() throws Exception {
        // 端到端：依赖欠款总览接口
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000)).andExpect(status().isCreated());

        assertThat(overview(s.carolUser, s.tripId, "")).containsExactly(owes(s.aliceMember, s.meMember, "JPY", 300_000, 100_000));
    }

    @Test
    void thePayerMayMarkARepaymentToSomeoneWhoLeft() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        leave(s.meMember);

        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].status").value("pending"));
    }

    @Test
    void thePayeeMayRecordARepaymentFromSomeoneWhoLeft() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        leave(s.aliceMember);

        createRepayment(s.meUser, s.tripId, alicePaysMe(s, uuid(), 100_000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].status").value("confirmed"));
    }

    @Test
    void theSameClientUuidInAnotherTripIsIndependent() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();
        aliceOwesMe(s, 300_000);
        expense(other.tripId, other.meMember, "JPY", other.aliceMember, 300_000);
        String u = uuid();

        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, u, 100_000)).andExpect(status().isCreated());
        createRepayment(other.aliceUser, other.tripId,
                RepaymentBody.of(u, other.aliceMember, other.meMember, "JPY", item("JPY", 100_000)))
                .andExpect(status().isCreated());
    }

    // ---- 幂等重放 ----

    @Test
    void anIdenticalReplayReturns200WithTheSameRowsAndCreatesNothing() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        expense(s.tripId, s.meMember, "HKD", s.aliceMember, 2_000);
        RepaymentBody body = RepaymentBody.of(uuid(), s.aliceMember, s.meMember, "HKD",
                item("JPY", 100_000, 5_000), item("HKD", 1_000));

        String first = createRepayment(s.aliceUser, s.tripId, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = createRepayment(s.aliceUser, s.tripId, body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Object>read(second, "$")).isEqualTo(JsonPath.<Object>read(first, "$"));
        assertThat(repaymentCount(s.tripId)).isEqualTo(2);
    }

    @Test
    void theOrderOfItemsDoesNotMatterForAReplay() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
        expense(s.tripId, s.meMember, "HKD", s.aliceMember, 2_000);
        String u = uuid();
        RepaymentBody a = RepaymentBody.of(u, s.aliceMember, s.meMember, "HKD", item("JPY", 100_000, 5_000), item("HKD", 1_000));
        RepaymentBody b = RepaymentBody.of(u, s.aliceMember, s.meMember, "HKD", item("HKD", 1_000), item("JPY", 100_000, 5_000));

        createRepayment(s.aliceUser, s.tripId, a).andExpect(status().isCreated());
        createRepayment(s.aliceUser, s.tripId, b).andExpect(status().isOk());
        assertThat(repaymentCount(s.tripId)).isEqualTo(2);
    }

    @Test
    void aReplayDoesNotCheckTheLimitAgain() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 100_000);
        RepaymentBody body = alicePaysMe(s, uuid(), 100_000);

        createRepayment(s.aliceUser, s.tripId, body).andExpect(status().isCreated());
        // 现在这一笔待确认已经占满了额度，一个新请求会超额，但原样重放仍是 200
        createRepayment(s.aliceUser, s.tripId, body).andExpect(status().isOk());
    }

    @Test
    void aReplayStillWorksAfterTheOtherPartyLeft() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        RepaymentBody body = alicePaysMe(s, uuid(), 100_000);
        createRepayment(s.aliceUser, s.tripId, body).andExpect(status().isCreated());
        leave(s.meMember);

        createRepayment(s.aliceUser, s.tripId, body).andExpect(status().isOk());
    }

    @Test
    void optionalFieldsLeftOutOfAReplayAreNotCompared() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        String u = uuid();
        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, u, 100_000).str("note", "原来的备注"))
                .andExpect(status().isCreated());

        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, u, 100_000)).andExpect(status().isOk());
    }

    @Test
    void aReplayWithDifferentContentOrCreatorOrOverACancelledRowIsTheSame409() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 500_000);
        expense(s.tripId, s.meMember, "HKD", s.aliceMember, 5_000);
        String u = uuid();
        RepaymentBody original = alicePaysMe(s, u, 100_000).str("note", "原来的备注");
        createRepayment(s.aliceUser, s.tripId, original).andExpect(status().isCreated());

        Map<String, RepaymentBody> variants = new LinkedHashMap<>();
        variants.put("金额不同", alicePaysMe(s, u, 200_000).str("note", "原来的备注"));
        variants.put("备注不同", alicePaysMe(s, u, 100_000).str("note", "别的备注"));
        variants.put("多一项", RepaymentBody.of(u, s.aliceMember, s.meMember, "JPY", item("JPY", 100_000), item("HKD", 1_000))
                .str("note", "原来的备注"));
        variants.put("实付币别不同", RepaymentBody.of(u, s.aliceMember, s.meMember, "HKD", item("JPY", 100_000, 5_000))
                .str("note", "原来的备注"));
        variants.put("收款方不同", RepaymentBody.of(u, s.aliceMember, s.bobMember, "JPY", item("JPY", 100_000))
                .str("note", "原来的备注"));
        variants.put("付款方不同", RepaymentBody.of(u, s.bobMember, s.meMember, "JPY", item("JPY", 100_000))
                .str("note", "原来的备注"));
        variants.put("多带了 expenseId", alicePaysMe(s, u, 100_000).str("note", "原来的备注").num("expenseId", 1));

        List<String> failures = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        for (Map.Entry<String, RepaymentBody> variant : variants.entrySet()) {
            MvcResult result = createRepayment(s.aliceUser, s.tripId, variant.getValue()).andReturn();
            if (result.getResponse().getStatus() != 409 || !"IDEMPOTENCY_KEY_REUSED".equals(codeOf(bodyOf(result)))) {
                failures.add(variant.getKey() + "：" + result.getResponse().getStatus() + " " + bodyOf(result));
            } else {
                messages.add(JsonPath.read(bodyOf(result), "$.message"));
            }
        }
        // 换一个人来重放同样的请求：创建者不同
        MvcResult byPayee = createRepayment(s.meUser, s.tripId, original).andReturn();
        // 第三个人
        MvcResult byThirdParty = createRepayment(s.carolUser, s.tripId, original).andReturn();
        for (MvcResult result : List.of(byPayee, byThirdParty)) {
            if (result.getResponse().getStatus() != 409 || !"IDEMPOTENCY_KEY_REUSED".equals(codeOf(bodyOf(result)))) {
                failures.add("换人重放：" + result.getResponse().getStatus() + " " + bodyOf(result));
            } else {
                messages.add(JsonPath.read(bodyOf(result), "$.message"));
            }
        }
        // 组里有一行已取消
        cancelRow(s.tripId, u, "JPY");
        MvcResult overCancelled = createRepayment(s.aliceUser, s.tripId, original).andReturn();
        if (overCancelled.getResponse().getStatus() != 409) {
            failures.add("已取消的组：" + overCancelled.getResponse().getStatus());
        } else {
            messages.add(JsonPath.read(bodyOf(overCancelled), "$.message"));
        }

        assertThat(failures).isEmpty();
        // 契约：这几种情况消息一样，调用者没法借提示区分
        assertThat(messages).hasSize(variants.size() + 3).containsOnly(messages.get(0));
        assertThat(repaymentCount(s.tripId)).isEqualTo(1);
    }

    @Test
    void aReplayOfAWhollyCancelledGroupIs409() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        RepaymentBody body = alicePaysMe(s, uuid(), 100_000);
        createRepayment(s.aliceUser, s.tripId, body).andExpect(status().isCreated());
        cancelRow(s.tripId, body.uuid(), "JPY");

        createRepayment(s.aliceUser, s.tripId, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(repaymentRows(s.tripId)).hasSize(1);
    }

    @Test
    void aNonMemberCannotProbeAClientUuidItIs404() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        RepaymentBody body = alicePaysMe(s, uuid(), 100_000);
        createRepayment(s.aliceUser, s.tripId, body).andExpect(status().isCreated());
        long outsider = user("r-outsider");

        createRepayment(outsider, s.tripId, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- 并发 ----

    @Test
    void twoSimultaneousSubmitsCreateOnceAndTheOtherGetsTheExistingGroup() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            aliceOwesMe(s, 300_000);
            RepaymentBody body = alicePaysMe(s, uuid(), 100_000);

            List<MvcResult> results = runConcurrently(2, () -> createRepayment(s.aliceUser, s.tripId, body).andReturn());

            assertThat(statuses(results)).containsExactlyInAnyOrder(200, 201);
            assertThat(JsonPath.<Object>read(bodyOf(results.get(0)), "$.items[0].id"))
                    .isEqualTo(JsonPath.<Object>read(bodyOf(results.get(1)), "$.items[0].id"));
            assertThat(repaymentCount(s.tripId)).isEqualTo(1);
        }
    }

    @Test
    void manySimultaneousSubmitsStillCreateOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            Scene s = new Scene();
            aliceOwesMe(s, 300_000);
            RepaymentBody body = alicePaysMe(s, uuid(), 100_000);

            List<MvcResult> results = runConcurrently(4, () -> createRepayment(s.aliceUser, s.tripId, body).andReturn());

            assertThat(statuses(results)).containsExactlyInAnyOrder(200, 200, 200, 201);
            assertThat(repaymentCount(s.tripId)).isEqualTo(1);
        }
    }

    @Test
    void simultaneousSubmitsOfAMultiItemGroupInDifferentItemOrdersDoNotDeadlock() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000);
            expense(s.tripId, s.meMember, "HKD", s.aliceMember, 2_000);
            String u = uuid();
            RepaymentBody a = RepaymentBody.of(u, s.aliceMember, s.meMember, "HKD", item("JPY", 100_000, 5_000), item("HKD", 1_000));
            RepaymentBody b = RepaymentBody.of(u, s.aliceMember, s.meMember, "HKD", item("HKD", 1_000), item("JPY", 100_000, 5_000));

            List<MvcResult> results = runConcurrently(2, new Callable<>() {
                private int next = 0;

                @Override
                public MvcResult call() throws Exception {
                    RepaymentBody mine;
                    synchronized (this) {
                        mine = (next++ == 0) ? a : b;
                    }
                    return createRepayment(s.aliceUser, s.tripId, mine).andReturn();
                }
            });

            assertThat(statuses(results)).containsExactlyInAnyOrder(200, 201);
            assertThat(repaymentCount(s.tripId)).isEqualTo(2);
        }
    }

    @Test
    void simultaneousSubmitsWithDifferentContentCreateOnceAndConflictOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            aliceOwesMe(s, 300_000);
            String u = uuid();
            RepaymentBody a = alicePaysMe(s, u, 100_000);
            RepaymentBody b = alicePaysMe(s, u, 200_000);

            List<MvcResult> results = runConcurrently(2, new Callable<>() {
                private int next = 0;

                @Override
                public MvcResult call() throws Exception {
                    RepaymentBody mine;
                    synchronized (this) {
                        mine = (next++ == 0) ? a : b;
                    }
                    return createRepayment(s.aliceUser, s.tripId, mine).andReturn();
                }
            });

            assertThat(statuses(results)).containsExactlyInAnyOrder(201, 409);
            assertThat(repaymentCount(s.tripId)).isEqualTo(1);
        }
    }

    @Test
    void twoSimultaneousRepaymentsThatTogetherExceedTheDebtLetOnlyOneThrough() throws Exception {
        for (int round = 0; round < 5; round++) {
            Scene s = new Scene();
            aliceOwesMe(s, 100_000);
            // 各自 60_000 都不超，合起来 120_000 超过 100_000
            RepaymentBody a = alicePaysMe(s, uuid(), 60_000);
            RepaymentBody b = alicePaysMe(s, uuid(), 60_000);

            List<MvcResult> results = runConcurrently(2, new Callable<>() {
                private int next = 0;

                @Override
                public MvcResult call() throws Exception {
                    RepaymentBody mine;
                    synchronized (this) {
                        mine = (next++ == 0) ? a : b;
                    }
                    return createRepayment(s.aliceUser, s.tripId, mine).andReturn();
                }
            });

            assertThat(statuses(results)).containsExactlyInAnyOrder(201, 400);
            MvcResult rejected = results.stream().filter(r -> r.getResponse().getStatus() == 400).findFirst().orElseThrow();
            assertThat(codeOf(bodyOf(rejected))).isEqualTo("REPAYMENT_EXCEEDS_DEBT");
            assertThat(repaymentCount(s.tripId)).isEqualTo(1);
        }
    }

    // ---- 权限 ----

    @Test
    void aThirdMemberCannotCreateARepaymentBetweenTwoOthers() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        assertRejected(s.carolUser, s, alicePaysMe(s, uuid(), 100_000), 403, "FORBIDDEN");
    }

    @Test
    void aNonMemberGets404() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        long outsider = user("r-outsider");

        assertRejected(outsider, s, alicePaysMe(s, uuid(), 100_000), 404, "NOT_FOUND");
    }

    @Test
    void aCreatorWhoLeftGets404() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        leave(s.aliceMember);

        assertRejected(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000), 404, "NOT_FOUND");
    }

    @Test
    void aDeletedTripGets404() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        deleteTrip(s.tripId);

        assertRejected(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000), 404, "NOT_FOUND");
    }

    @Test
    void withoutIdentityIs401() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(post("/trips/" + s.tripId + "/repayments")
                        .contentType("application/json").content(alicePaysMe(s, uuid(), 100_000).json()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void memberIdsAreMemberIdsNotUserIds() throws Exception {
        long ownerUser = user("r-owner");
        long tripId = trip(ownerUser);
        long owner = memberId(tripId, ownerUser);
        // X 的 userId 恰好等于 Y 的 memberId；Y 欠 owner，X 不欠
        long userX = userWithId(EXPLICIT_ID, "r-x");
        long memberX = member(tripId, userX, "member", "active");
        long userY = user("r-y");
        long memberY = memberWithId(EXPLICIT_ID, tripId, userY, "member", "active");
        expense(tripId, owner, "JPY", memberY, 200_000);

        // 请求里的 fromMemberId = EXPLICIT_ID 是 Y 的 memberId：当前用户 Y 是付款方，成功
        RepaymentBody byY = RepaymentBody.of(uuid(), EXPLICIT_ID, owner, "JPY", item("JPY", 100_000));
        createRepayment(userY, tripId, byY).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].createdByMemberId").value(memberY));
        // 当前用户 X 的 userId 恰好等于 fromMemberId，但他的 memberId 不是：不是当事人，403
        RepaymentBody byX = RepaymentBody.of(uuid(), EXPLICIT_ID, owner, "JPY", item("JPY", 100_000));
        createRepayment(userX, tripId, byX).andExpect(status().isForbidden());
        assertThat(memberX).isNotEqualTo(EXPLICIT_ID);
        assertThat(repaymentCount(tripId)).isEqualTo(1);
    }

    // ---- 请求校验（400） ----

    @Test
    void everyRequiredFieldMustBePresent() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        List<String> failures = new ArrayList<>();
        for (String field : List.of("clientUuid", "fromMemberId", "toMemberId", "paidCurrency", "items")) {
            MvcResult result = createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000).without(field)).andReturn();
            String json = bodyOf(result);
            boolean ok = result.getResponse().getStatus() == 400
                    && "VALIDATION_FAILED".equals(codeOf(json))
                    && JsonPath.<List<String>>read(json, "$.fields[*].field").contains(field);
            if (!ok) {
                failures.add(field + "：" + result.getResponse().getStatus() + " " + json);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(repaymentCount(s.tripId)).isZero();
    }

    @Test
    void clientUuidMustBeAUuid() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        assertInvalid(s.aliceUser, s, alicePaysMe(s, "not-a-uuid", 100_000), "clientUuid");
    }

    @Test
    void thePayerAndThePayeeMustDiffer() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        assertInvalid(s.aliceUser, s,
                RepaymentBody.of(uuid(), s.aliceMember, s.aliceMember, "JPY", item("JPY", 100_000)), "toMemberId");
    }

    @Test
    void thePayerAndThePayeeMustBelongToTheTrip() throws Exception {
        Scene s = new Scene();
        Scene other = new Scene();
        aliceOwesMe(s, 300_000);

        // 创建者 alice 是付款方；收款方是别的行程的成员
        assertRejected(s.aliceUser, s,
                RepaymentBody.of(uuid(), s.aliceMember, other.meMember, "JPY", item("JPY", 100_000)),
                400, "MEMBER_NOT_IN_TRIP");
        // 创建者 me 是收款方；付款方根本不存在
        assertRejected(s.meUser, s,
                RepaymentBody.of(uuid(), 999_999_999L, s.meMember, "JPY", item("JPY", 100_000)),
                400, "MEMBER_NOT_IN_TRIP");
    }

    @Test
    void currenciesMustExist() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        assertInvalid(s.aliceUser, s,
                RepaymentBody.of(uuid(), s.aliceMember, s.meMember, "XXX", item("JPY", 100_000, 5_000)), "paidCurrency");
        assertInvalid(s.aliceUser, s,
                RepaymentBody.of(uuid(), s.aliceMember, s.meMember, "JPY", item("XXX", 100_000, 100_000)), "items");
    }

    @Test
    void itemsMustNotBeEmptyOrRepeatACurrency() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        assertInvalid(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000).items(), "items");
        assertInvalid(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000).raw("items", "null"), "items");
        assertInvalid(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000).items(item("JPY", 100_000), item("JPY", 100_000)), "items");
    }

    @Test
    void everyItemNeedsItsThreeFieldsAndPositiveAmounts() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        expense(s.tripId, s.meMember, "HKD", s.aliceMember, 5_000);

        List<String> badItems = List.of(
                "{\"clearedAmountMinor\":100000,\"paidAmountMinor\":100000}",
                "{\"clearedCurrency\":\"JPY\",\"paidAmountMinor\":100000}",
                "{\"clearedCurrency\":\"JPY\",\"clearedAmountMinor\":100000}",
                item("JPY", 0, 0),
                item("JPY", -100_000, -100_000));
        List<String> failures = new ArrayList<>();
        for (String bad : badItems) {
            int before = repaymentCount(s.tripId);
            MvcResult result = createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000).items(bad)).andReturn();
            String json = bodyOf(result);
            boolean ok = result.getResponse().getStatus() == 400 && "VALIDATION_FAILED".equals(codeOf(json))
                    && JsonPath.<List<String>>read(json, "$.fields[*].field").contains("items")
                    && repaymentCount(s.tripId) == before;
            if (!ok) {
                failures.add(bad + " → " + result.getResponse().getStatus() + " " + json);
            }
        }
        // 跨币别时实付金额也要 > 0
        assertInvalid(s.aliceUser, s,
                RepaymentBody.of(uuid(), s.aliceMember, s.meMember, "JPY", item("HKD", 1_000, 0)), "items");
        assertThat(failures).isEmpty();
    }

    @Test
    void paidMustEqualClearedWhenTheCurrenciesAreTheSame() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        assertInvalid(s.aliceUser, s,
                RepaymentBody.of(uuid(), s.aliceMember, s.meMember, "JPY", item("JPY", 100_000, 90_000)), "items");
    }

    @Test
    void amountsMustBeWholeMultiplesOfTheRoundingUnit() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        expense(s.tripId, s.meMember, "HKD", s.aliceMember, 5_000);

        // 销帐 JPY 1000.50
        assertRejected(s.aliceUser, s, alicePaysMe(s, uuid(), 100_050), 400, "AMOUNT_NOT_ROUNDABLE");
        // 实付 JPY 150.50：实付币别 JPY 的取整单位是 100
        assertRejected(s.aliceUser, s,
                RepaymentBody.of(uuid(), s.aliceMember, s.meMember, "JPY", item("HKD", 2_000, 15_050)),
                400, "AMOUNT_NOT_ROUNDABLE");
    }

    @Test
    void theNoteIsLimitedTo512Characters() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000).str("note", "a".repeat(512)))
                .andExpect(status().isCreated());
        assertInvalid(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000).str("note", "a".repeat(513)), "note");
    }

    @Test
    void aMalformedBodyIs400NotAServerError() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        List<RepaymentBody> bad = List.of(
                alicePaysMe(s, uuid(), 100_000).raw("items", "\"abc\""),
                alicePaysMe(s, uuid(), 100_000).items("{\"clearedCurrency\":\"JPY\",\"clearedAmountMinor\":\"abc\",\"paidAmountMinor\":1}"),
                alicePaysMe(s, uuid(), 100_000).raw("fromMemberId", "\"abc\""));
        List<String> failures = new ArrayList<>();
        for (RepaymentBody body : bad) {
            MvcResult result = createRepayment(s.aliceUser, s.tripId, body).andReturn();
            if (result.getResponse().getStatus() != 400) {
                failures.add(body.json() + " → " + result.getResponse().getStatus());
            }
        }
        assertThat(failures).isEmpty();
        assertThat(repaymentCount(s.tripId)).isZero();
    }

    // ---- 额度（REPAYMENT_EXCEEDS_DEBT） ----

    @Test
    void moreThanTheDebtIsRejected() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 300_100));
    }

    @Test
    void anAbsurdlyLargeAmountIsRejectedNotAServerError() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 9_223_372_036_854_775_800L));
    }

    @Test
    void noDebtAtAllIsTheSameError() throws Exception {
        Scene s = new Scene();

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000));
    }

    @Test
    void aDebtInTheOtherDirectionAllowsNothing() throws Exception {
        Scene s = new Scene();
        expense(s.tripId, s.aliceMember, "JPY", s.meMember, 300_000);   // 是 me 欠 alice

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000));
    }

    @Test
    void theLimitIsTheNetDebtNotTheRawOne() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        expense(s.tripId, s.aliceMember, "JPY", s.meMember, 100_000);   // 抵掉 100_000，净额 200_000

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 250_000));
        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 200_000)).andExpect(status().isCreated());
    }

    @Test
    void pendingRepaymentsInTheSameDirectionTakeUpTheLimit() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 200_000);

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 150_000));
        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000)).andExpect(status().isCreated());
    }

    @Test
    void pendingRepaymentsOfOtherDirectionsPeopleOrCurrenciesAndCancelledOnesDoNot() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        pending(s.tripId, s.meMember, s.aliceMember, "JPY", 100_000);   // 反方向
        pending(s.tripId, s.bobMember, s.meMember, "JPY", 100_000);     // 别人
        pending(s.tripId, s.aliceMember, s.meMember, "HKD", 100_000);   // 别的币别
        cancelled(s.tripId, s.aliceMember, s.meMember, "JPY", 200_000); // 已取消

        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 300_000)).andExpect(status().isCreated());
    }

    @Test
    void whenPendingAlreadyExceedsTheDebtNothingIsLeft() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 100_000);
        pending(s.tripId, s.aliceMember, s.meMember, "JPY", 150_000);

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 100));
    }

    @Test
    void confirmedRepaymentsReduceTheLimit() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        confirmed(s.tripId, s.aliceMember, s.meMember, "JPY", 200_000);

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 150_000));
        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000)).andExpect(status().isCreated());
    }

    @Test
    void personalAndDeletedExpensesGiveNoLimit() throws Exception {
        Scene s = new Scene();
        personalExpense(s.tripId, s.aliceMember, "JPY", 300_000);
        softDeleteExpense(expense(s.tripId, s.meMember, "JPY", s.aliceMember, 300_000));

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 100));
    }

    @Test
    void theLimitAppliesPerCurrencyAndOneBadItemRejectsTheWholeRequest() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);     // 只有 JPY 的欠款
        RepaymentBody body = RepaymentBody.of(uuid(), s.aliceMember, s.meMember, "JPY",
                item("JPY", 100_000), item("HKD", 1_000, 5_000));

        assertExceedsDebt(s.aliceUser, s, body);
    }

    @Test
    void thePayeeRecordingItAsReceivedIsLimitedToo() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        assertExceedsDebt(s.meUser, s, alicePaysMe(s, uuid(), 400_000));
    }

    @Test
    void theDebtToSomeoneWhoLeftStillLimitsAndAllows() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        leave(s.meMember);

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 400_000));
        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 300_000)).andExpect(status().isCreated());
    }

    // ---- 错误的先后 ----

    @Test
    void notBeingTheCreatorsPartyBeatsValidation() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        // 请求本身也有错（paidCurrency 不在币别表里），但 carol 不是当事人：先 403
        assertRejected(s.carolUser, s, alicePaysMe(s, uuid(), 100_000).str("paidCurrency", "XXX"), 403, "FORBIDDEN");
    }

    @Test
    void notBeingAMemberBeatsValidation() throws Exception {
        Scene s = new Scene();
        long outsider = user("r-outsider");

        // 请求本身也有错（paidCurrency 不在币别表里），但 outsider 不在这个行程里：先 404
        assertRejected(outsider, s, alicePaysMe(s, uuid(), 100_000).str("paidCurrency", "XXX"), 404, "NOT_FOUND");
    }

    @Test
    void validationBeatsTheLimit() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 100_000);

        // 既超额又不是取整单位的整数倍：报取整
        assertRejected(s.aliceUser, s, alicePaysMe(s, uuid(), 100_050), 400, "AMOUNT_NOT_ROUNDABLE");
    }

    @Test
    void anInvisibleExpenseIdBeatsOtherValidation() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);

        // 请求本身也有错（paidCurrency 不在币别表里），但 expenseId 看不见：先 404
        assertRejected(s.aliceUser, s,
                alicePaysMe(s, uuid(), 100_000).str("paidCurrency", "XXX").num("expenseId", 987_654_321L), 404, "NOT_FOUND");
    }

    // ---- 针对某笔支出还款（expenseId） ----

    /** me 付了 A（alice 的份 100_000）和 B（alice 的份 200_000），alice 一共欠 me 300_000 */
    private long[] twoExpensesAliceOwesMe(Scene s) {
        long a = expense(s.tripId, s.meMember, "JPY", s.meMember, 100_000, s.aliceMember, 100_000);
        long b = expense(s.tripId, s.meMember, "JPY", s.aliceMember, 200_000);
        return new long[]{a, b};
    }

    @Test
    void repayingForOneExpenseRecordsItsId() throws Exception {
        Scene s = new Scene();
        long[] e = twoExpensesAliceOwesMe(s);

        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000).num("expenseId", e[0]))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].expenseId").value(e[0]));
        assertThat(repaymentRows(s.tripId)).singleElement().satisfies(row -> assertThat(row.expenseId()).isEqualTo(e[0]));
    }

    @Test
    void theLimitForOneExpenseIsTheShareOfThePayerInIt() throws Exception {
        Scene s = new Scene();
        long[] e = twoExpensesAliceOwesMe(s);

        // 整对欠款有 300_000，但这笔里 alice 的份只有 100_000
        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 150_000).num("expenseId", e[0]));
        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000).num("expenseId", e[0]))
                .andExpect(status().isCreated());
    }

    @Test
    void earlierRepaymentsForTheSameExpenseUseUpItsShareWhetherPendingOrConfirmed() throws Exception {
        Scene s = new Scene();
        long[] e = twoExpensesAliceOwesMe(s);
        repayment(s.tripId, s.aliceMember, s.meMember, "JPY", 30_000, "JPY", 30_000, "confirmed", false, uuid(), e[0]);
        repayment(s.tripId, s.aliceMember, s.meMember, "JPY", 30_000, "JPY", 30_000, "pending", false, uuid(), e[0]);
        // 这笔剩 100_000 − 60_000 = 40_000

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 50_000).num("expenseId", e[0]));
        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 40_000).num("expenseId", e[0]))
                .andExpect(status().isCreated());
    }

    @Test
    void cancelledRepaymentsAndRepaymentsForOtherExpensesDoNotUseUpTheShare() throws Exception {
        Scene s = new Scene();
        long[] e = twoExpensesAliceOwesMe(s);
        repayment(s.tripId, s.aliceMember, s.meMember, "JPY", 50_000, "JPY", 50_000, "pending", true, uuid(), e[0]);
        repayment(s.tripId, s.aliceMember, s.meMember, "JPY", 100_000, "JPY", 100_000, "confirmed", false, uuid(), e[1]);
        // A 的份还是 100_000；整对欠款剩 200_000

        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000).num("expenseId", e[0]))
                .andExpect(status().isCreated());
    }

    @Test
    void theLimitForOneExpenseIsAlsoCappedByThePairsNetDebt() throws Exception {
        Scene s = new Scene();
        long a = expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        expense(s.tripId, s.aliceMember, "JPY", s.meMember, 70_000);   // 反过来 me 欠 alice 70_000，净额 30_000

        assertExceedsDebt(s.aliceUser, s, alicePaysMe(s, uuid(), 50_000).num("expenseId", a));
        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 30_000).num("expenseId", a))
                .andExpect(status().isCreated());
    }

    @Test
    void anExpenseTheCreatorCannotSeeIs404() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        Scene other = new Scene();
        long otherTripExpense = expense(other.tripId, other.meMember, "JPY", other.aliceMember, 100_000);
        long deleted = expense(s.tripId, s.meMember, "JPY", s.aliceMember, 100_000);
        softDeleteExpense(deleted);
        long carolsPersonal = personalExpense(s.tripId, s.carolMember, "JPY", 100_000);

        List<String> failures = new ArrayList<>();
        for (long id : new long[]{987_654_321L, otherTripExpense, deleted, carolsPersonal}) {
            MvcResult result = createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, uuid(), 100_000).num("expenseId", id)).andReturn();
            if (result.getResponse().getStatus() != 404 || !bodyOf(result).contains("\"NOT_FOUND\"")) {
                failures.add("expenseId=" + id + " → " + result.getResponse().getStatus());
            }
        }
        assertThat(failures).isEmpty();
        assertThat(repaymentCount(s.tripId)).isZero();
    }

    /** 看得见与否按创建者算，不是按付款方：收款方（me）创建时，付款方（alice）的个人支出对 me 也是 404，不能是 400 */
    @Test
    void thePayeeCreatingCannotSeeThePayersPersonalExpense() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        long alicesPersonal = personalExpense(s.tripId, s.aliceMember, "JPY", 100_000);

        assertRejected(s.meUser, s, alicePaysMe(s, uuid(), 100_000).num("expenseId", alicesPersonal), 404, "NOT_FOUND");
    }

    @Test
    void thePayeeCanCreateAConfirmedRepaymentForOneExpense() throws Exception {
        Scene s = new Scene();
        long[] e = twoExpensesAliceOwesMe(s);

        createRepayment(s.meUser, s.tripId, alicePaysMe(s, uuid(), 100_000).num("expenseId", e[0]))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].expenseId").value(e[0]))
                .andExpect(jsonPath("$.items[0].status").value("confirmed"));
    }

    @Test
    void anExpenseThatDoesNotQualifyIs400() throws Exception {
        Scene s = new Scene();
        aliceOwesMe(s, 300_000);
        long paidByCarol = expense(s.tripId, s.carolMember, "JPY", s.aliceMember, 100_000);          // 不是 me 付的
        long aliceNotInIt = expense(s.tripId, s.meMember, "JPY", s.bobMember, 100_000);              // alice 不在分摊里
        long inHkd = expense(s.tripId, s.meMember, "HKD", s.aliceMember, 5_000);                     // 币别不符
        long alicesPersonal = personalExpense(s.tripId, s.aliceMember, "JPY", 100_000);              // 个人支出

        assertInvalid(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000).num("expenseId", paidByCarol), "expenseId");
        assertInvalid(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000).num("expenseId", aliceNotInIt), "expenseId");
        assertInvalid(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000).num("expenseId", inHkd), "expenseId");
        assertInvalid(s.aliceUser, s, alicePaysMe(s, uuid(), 100_000).num("expenseId", alicesPersonal), "expenseId");
    }

    @Test
    void withAnExpenseIdThereCanBeOnlyOneItem() throws Exception {
        Scene s = new Scene();
        long[] e = twoExpensesAliceOwesMe(s);
        expense(s.tripId, s.meMember, "HKD", s.aliceMember, 5_000);

        assertInvalid(s.aliceUser, s,
                alicePaysMe(s, uuid(), 100_000).items(item("JPY", 100_000), item("HKD", 1_000)).num("expenseId", e[0]), "items");
    }

    @Test
    void aReplayComparesTheExpenseId() throws Exception {
        Scene s = new Scene();
        long[] e = twoExpensesAliceOwesMe(s);
        String u = uuid();
        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, u, 100_000).num("expenseId", e[0])).andExpect(status().isCreated());

        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, u, 100_000).num("expenseId", e[0])).andExpect(status().isOk());
        createRepayment(s.aliceUser, s.tripId, alicePaysMe(s, u, 100_000).num("expenseId", e[1]))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }
}
