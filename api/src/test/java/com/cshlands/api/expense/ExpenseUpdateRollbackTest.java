package com.cshlands.api.expense;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修改要在一个事务里：更新 expense、删旧分摊、写新分摊。中途失败，三样都要回到修改前。
 * <p>
 * 和创建的回滚测试一样，用临时给 expense_split 加 CHECK 约束让写新分摊失败。区别是这张表里已经有旧的分摊行，
 * 加约束时 MySQL 会检查它们，所以约束不能是"金额 < 0"这种旧行也不满足的；这里选一个只有新请求才会写出来的金额。
 * 约束在 finally 里删除，不影响别的测试类。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class ExpenseUpdateRollbackTest extends ExpenseTestBase {

    private static final String FAILING_CHECK = "chk_test_fail_split_update";
    /** 新请求里才会出现的分摊金额（旧的分摊是 33400 / 33300） */
    private static final long POISONED_AMOUNT = 777_700;

    @Test
    void aFailureWhileWritingTheNewSplitsRestoresTheOldExpense() throws Exception {
        Scene s = new Scene();
        long id = createdExpense(s);
        Map<Long, Long> splitsBefore = splitsOf(id);

        int httpStatus = updateWhileSplitInsertsFail(s, id);

        assertThat(httpStatus).as("写分摊失败应该是服务端错误").isGreaterThanOrEqualTo(500);
        assertThat(versionOf(id)).as("version 必须回到修改前").isZero();
        assertThat(titleOf(id)).as("标题必须回到修改前").isEqualTo("拉麵");
        assertThat(splitsOf(id)).as("旧的分摊行必须还在").isEqualTo(splitsBefore);
    }

    @Test
    void afterTheFailureTheSameVersionCanBeUsedAgain() throws Exception {
        Scene s = new Scene();
        long id = createdExpense(s);
        updateWhileSplitInsertsFail(s, id);

        // 故障排除后，同一个 version 重试：成功，version 变成 1
        update(s.meUser, s.tripId, id, poisonedBody(s, 0).str("title", "重试成功"))
                .andExpect(status().isOk());
        assertThat(versionOf(id)).isEqualTo(1);
        assertThat(splitsOf(id)).containsOnly(Map.entry(s.meMember, POISONED_AMOUNT), Map.entry(s.aliceMember, 800_000 - POISONED_AMOUNT));
    }

    // ---- 工具 ----

    private long createdExpense(Scene s) throws Exception {
        String json = create(s.meUser, s.tripId, Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idOf(json);
    }

    /** 把分摊改成 custom：me 拿 POISONED_AMOUNT，alice 拿其余，总额 800000 */
    private Body poisonedBody(Scene s, long version) {
        return Body.custom(uuid(), 800_000, "JPY", s.meMember, s.meMember, POISONED_AMOUNT, s.aliceMember, 800_000 - POISONED_AMOUNT)
                .without("clientUuid").num("version", version).str("title", "不该生效");
    }

    /** 让写入金额为 POISONED_AMOUNT 的分摊行失败，发一次修改请求，返回 HTTP 状态；用完一定把约束删掉 */
    private int updateWhileSplitInsertsFail(Scene s, long id) {
        dropFailingCheck();
        jdbc.execute("ALTER TABLE expense_split ADD CONSTRAINT " + FAILING_CHECK + " CHECK (amount_minor <> " + POISONED_AMOUNT + ")");
        try {
            return update(s.meUser, s.tripId, id, poisonedBody(s, 0)).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            // 没有被统一异常处理器接住的异常，MockMvc 会直接抛出来：等价于 500
            return 500;
        } finally {
            dropFailingCheck();
        }
    }

    private void dropFailingCheck() {
        try {
            jdbc.execute("ALTER TABLE expense_split DROP CHECK " + FAILING_CHECK);
        } catch (RuntimeException ignored) {
            // 本来就没有
        }
    }
}
