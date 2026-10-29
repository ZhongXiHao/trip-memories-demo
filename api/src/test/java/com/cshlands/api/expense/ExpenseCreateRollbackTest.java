package com.cshlands.api.expense;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写 expense 和 expense_split 必须在同一个事务里：中途失败，库里不能留下半笔账。
 * <p>
 * 单独成一个类，因为它要临时给 expense_split 加一个永远不满足的 CHECK 约束，让每次插入分摊行都失败。
 * 这样不用引用还没写好的 Mapper（做不了 @MockitoSpyBean），也不依赖实现里谁先插谁后插：
 * 只要实现先插了 expense 再插分摊，这一笔 expense 就必须被回滚掉。
 * 约束在 finally 里删除，不影响别的测试类。
 * （不用触发器：测试库的连接用户没有 SUPER 权限，开着 binlog 时建不了触发器；ALTER 则够用。）
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class ExpenseCreateRollbackTest extends ExpenseTestBase {

    private static final String FAILING_CHECK = "chk_test_fail_split_insert";

    @Test
    void aFailureWhileWritingSplitsLeavesNoExpenseBehind() throws Exception {
        Scene s = new Scene();
        Body body = Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember);

        int httpStatus = createWhileSplitInsertsFail(s, body);

        assertThat(httpStatus).as("写分摊失败应该是服务端错误").isGreaterThanOrEqualTo(500);
        assertThat(expenseRows(s.tripId)).as("expense 行必须被回滚").isZero();
        assertThat(splitRows(s.tripId)).isZero();
    }

    @Test
    void aFailedWriteDoesNotUseUpTheClientUuid() throws Exception {
        Scene s = new Scene();
        Body body = Body.equal(uuid(), 100000, "JPY", s.meMember, s.meMember, s.aliceMember, s.bobMember);

        createWhileSplitInsertsFail(s, body);

        // 故障排除后，同一个 clientUuid 重试：是首次创建（201），不会被上次回滚掉的那一行挡住
        create(s.meUser, s.tripId, body).andExpect(status().isCreated());
        assertThat(expenseRows(s.tripId)).isEqualTo(1);
        assertThat(splitRows(s.tripId)).isEqualTo(3);
    }

    /** 让所有插入 expense_split 的语句失败，发一次创建请求，返回 HTTP 状态；用完一定把约束删掉 */
    private int createWhileSplitInsertsFail(Scene s, Body body) {
        dropFailingCheck();
        // 分摊金额都 >= 0，要求 < 0 就是永远不满足；表里此时没有行，所以加约束本身不会失败
        jdbc.execute("ALTER TABLE expense_split ADD CONSTRAINT " + FAILING_CHECK + " CHECK (amount_minor < 0)");
        try {
            return create(s.meUser, s.tripId, body).andReturn().getResponse().getStatus();
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
