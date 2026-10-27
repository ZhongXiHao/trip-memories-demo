package com.cshlands.api.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ErrorCode 必须和 docs/api-contract.md「通用约定」里的错误码表逐行一致。
 * 下面的表是照契约抄的字面量，不是从枚举里生成的，这样枚举改了名或状态码，这里会立刻红。
 */
class ErrorCodeTest {

    /** {状态码, code}，顺序同契约 */
    private static final Object[][] CONTRACT = {
            {400, "VALIDATION_FAILED"},
            {400, "AMOUNT_NOT_ROUNDABLE"},
            {400, "SPLIT_SUM_MISMATCH"},
            {400, "MEMBER_NOT_IN_TRIP"},
            {400, "MEMBER_LEFT"},
            {400, "REPAYMENT_EXCEEDS_DEBT"},
            {401, "UNAUTHENTICATED"},
            {403, "FORBIDDEN"},
            {404, "NOT_FOUND"},
            {409, "VERSION_CONFLICT"},
            {409, "IDEMPOTENCY_KEY_REUSED"},
            {409, "ALREADY_MEMBER"},
            {409, "OWNER_MUST_TRANSFER_FIRST"},
            {409, "CANNOT_REMOVE_OWNER"},
            {409, "REPAYMENT_ALREADY_CONFIRMED"},
    };

    @Test
    void matchesTheContractTable() {
        Map<String, ErrorCode> byCode = Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(ErrorCode::code, Function.identity()));

        List<String> problems = new ArrayList<>();
        for (Object[] row : CONTRACT) {
            int status = (Integer) row[0];
            String code = (String) row[1];
            ErrorCode errorCode = byCode.get(code);
            if (errorCode == null) {
                problems.add(code + "：枚举里没有");
            } else if (errorCode.status().value() != status) {
                problems.add(code + "：状态码应为 " + status + "，实际 " + errorCode.status().value());
            }
        }
        assertThat(byCode).hasSize(CONTRACT.length);
        assertThat(problems).isEmpty();
    }

}
