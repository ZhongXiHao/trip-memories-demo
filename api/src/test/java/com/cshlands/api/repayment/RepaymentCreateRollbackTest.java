package com.cshlands.api.repayment;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 一次转账结清多条欠款，是多行还款：中途失败，库里不能留下半组。
 * <p>
 * 单独成一个类，因为它要临时给 repayment 加一个 CHECK 约束，让某个金额的那一行插不进去。
 * 不引用还没写好的 Mapper（做不了 @MockitoSpyBean），也不依赖实现里先插哪一行：
 * 两个测试各让一个币别的那一行失败，总有一个测试里失败的是"后插的那一行"，这时先插的那一行必须被回滚。
 * 约束在 finally 里删除，不影响别的测试类。（不用触发器：测试库的连接用户没有 SUPER 权限。）
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class RepaymentCreateRollbackTest extends RepaymentTestBase {

    private static final String FAILING_CHECK = "chk_test_fail_repayment_insert";

    /** JPY 的项销帐 777_700，HKD 的项销帐 2_000；alice 欠 me 这两个币别 */
    private static final long JPY_AMOUNT = 777_700;
    private static final long HKD_AMOUNT = 2_000;

    private RepaymentBody twoCurrencies(Scene s, String uuid) {
        return RepaymentBody.of(uuid, s.aliceMember, s.meMember, "HKD",
                item("JPY", JPY_AMOUNT, 40_000), item("HKD", HKD_AMOUNT, HKD_AMOUNT));
    }

    private void owe(Scene s) {
        expense(s.tripId, s.meMember, "JPY", s.aliceMember, 1_000_000);
        expense(s.tripId, s.meMember, "HKD", s.aliceMember, 5_000);
    }

    @Test
    void aFailureOnTheJpyRowLeavesNoRowOfTheGroupBehind() throws Exception {
        Scene s = new Scene();
        owe(s);

        int httpStatus = createWhileRowsOfAmountFail(s, twoCurrencies(s, uuid()), JPY_AMOUNT);

        assertThat(httpStatus).as("写某一行失败应该是服务端错误").isGreaterThanOrEqualTo(500);
        assertThat(repaymentCount(s.tripId)).as("同一组里先写进去的行必须被回滚").isZero();
    }

    @Test
    void aFailureOnTheHkdRowLeavesNoRowOfTheGroupBehind() throws Exception {
        Scene s = new Scene();
        owe(s);

        int httpStatus = createWhileRowsOfAmountFail(s, twoCurrencies(s, uuid()), HKD_AMOUNT);

        assertThat(httpStatus).isGreaterThanOrEqualTo(500);
        assertThat(repaymentCount(s.tripId)).isZero();
    }

    @Test
    void aFailedWriteDoesNotUseUpTheClientUuid() throws Exception {
        Scene s = new Scene();
        owe(s);
        RepaymentBody body = twoCurrencies(s, uuid());

        createWhileRowsOfAmountFail(s, body, JPY_AMOUNT);

        // 故障排除后，同一个 clientUuid 重试：是首次创建（201），两行都在，不会被上次回滚掉的行挡住
        createRepayment(s.aliceUser, s.tripId, body).andExpect(status().isCreated());
        assertThat(repaymentCount(s.tripId)).isEqualTo(2);
    }

    /** 让销帐金额等于 failingAmount 的那一行插入失败，发一次创建请求，返回 HTTP 状态；用完一定把约束删掉 */
    private int createWhileRowsOfAmountFail(Scene s, RepaymentBody body, long failingAmount) {
        dropFailingCheck();
        // 表里此时没有这个金额的行，所以加约束本身不会失败
        jdbc.execute("ALTER TABLE repayment ADD CONSTRAINT " + FAILING_CHECK
                + " CHECK (cleared_amount_minor <> " + failingAmount + ")");
        try {
            return createRepayment(s.aliceUser, s.tripId, body).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            // 没有被统一异常处理器接住的异常，MockMvc 会直接抛出来：等价于 500
            return 500;
        } finally {
            dropFailingCheck();
        }
    }

    private void dropFailingCheck() {
        try {
            jdbc.execute("ALTER TABLE repayment DROP CHECK " + FAILING_CHECK);
        } catch (RuntimeException ignored) {
            // 本来就没有
        }
    }
}
