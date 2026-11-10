package com.cshlands.api.command;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips/{tripId}/commands/parse：请求本身的校验和权限、不是指令、认不出的指令、暂未开放的指令、查看类指令。
 * `/pay` 的解析规则见 {@link CommandPayParseTest}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class CommandParseTest extends CommandTestBase {

    // ---- 请求本身 ----

    @Test
    void missingInputIsRejected() throws Exception {
        Scene s = new Scene();

        parseRaw(s.meUser, s.tripId, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("input"));
        parseRaw(s.meUser, s.tripId, "{\"input\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("input"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "　"})
    void blankInputIsRejected(String input) throws Exception {
        Scene s = new Scene();

        parseRequest(s.meUser, s.tripId, input)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("input"));
    }

    @Test
    void inputOf500CharactersIsAcceptedAnd501IsRejected() throws Exception {
        Scene s = new Scene();
        String head = "/pay 1200jpy ";

        // 500 字符的请求本身合法（标题太长是解析结果，不是 4xx）
        parseRequest(s.meUser, s.tripId, head + "a".repeat(500 - head.length()))
                .andExpect(status().isOk());
        parseRequest(s.meUser, s.tripId, head + "a".repeat(501 - head.length()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("input"));
    }

    @Test
    void malformedBodyIsRejected() throws Exception {
        Scene s = new Scene();

        parseRaw(s.meUser, s.tripId, "{")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void withoutIdentityIs401() throws Exception {
        Scene s = new Scene();

        mockMvc.perform(post("/trips/" + s.tripId + "/commands/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"/owe\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void nonMemberGets404() throws Exception {
        Scene s = new Scene();
        long outsider = user("Outsider");

        parseRequest(outsider, s.tripId, "/pay 1200jpy 拉麵")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void leftMemberGets404() throws Exception {
        Scene s = new Scene();

        parseRequest(s.leoUser, s.tripId, "/pay 1200jpy 拉麵")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void deletedTripGets404() throws Exception {
        Scene s = new Scene();
        jdbc.update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", s.tripId);

        parseRequest(s.meUser, s.tripId, "/pay 1200jpy 拉麵")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void parsingNeverWrites() throws Exception {
        Scene s = new Scene();

        parse(s, "/pay 1200jpy 拉麵 @Ken");
        parse(s, "/pay 拉麵");
        parse(s, "/owe");

        assertThat(expenseRows(s.tripId)).isZero();
    }

    // ---- 不是指令、认不出的指令 ----

    @Test
    void textWithoutSlashIsNotACommand() throws Exception {
        Scene s = new Scene();

        // 严格模式不处理自由文本，哪怕里面有能认出的金额和成员
        Parsed r = parse(s, "  D3 晚餐 1200jpy @Ken ");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.at("$.command")).isNull();
        assertThat(r.at("$.kind")).isNull();
        assertThat(r.at("$.draft")).isNull();
        assertThat(r.at("$.preview")).isNull();
        assertThat(r.at("$.view")).isNull();
        assertThat(r.issues()).containsExactly("NOT_A_COMMAND:D3 晚餐 1200jpy @Ken");
        assertThat(r.warnings()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/foo", "/", "/pay1200jpy", "/payy"})
    void unknownCommand(String command) throws Exception {
        Scene s = new Scene();

        // 指令是从 / 到第一个空白为止的整个词："/pay1200jpy" 不是 /pay
        Parsed r = parse(s, command + " 拉麵");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.at("$.command")).isNull();
        assertThat(r.at("$.kind")).isNull();
        assertThat(r.at("$.draft")).isNull();
        assertThat(r.issues()).containsExactly("UNKNOWN_COMMAND:" + command);
    }

    // ---- 暂未开放 ----

    @ParameterizedTest
    @ValueSource(strings = {"todo", "visited", "missed", "skip", "mood", "note"})
    void reservedCommandsAreUnsupportedNotUnknown(String name) throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/" + name + " 淺草寺");

        assertThat(r.status()).isEqualTo("unsupported");
        assertThat(r.str("$.command")).isEqualTo(name);
        assertThat(r.at("$.kind")).isNull();
        assertThat(r.at("$.draft")).isNull();
        assertThat(r.at("$.preview")).isNull();
        assertThat(r.issues()).containsExactly("COMMAND_NOT_AVAILABLE:/" + name);
    }

    // ---- 查看类 ----

    @ParameterizedTest
    @CsvSource({
            "/owe, owe, debts",
            "/OWE, owe, debts",
            "/欠款, owe, debts",
            "/today, today, today_expenses",
            "/今天, today, today_expenses",
    })
    void viewCommands(String input, String command, String view) throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, input);

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.command")).isEqualTo(command);
        assertThat(r.str("$.kind")).isEqualTo("view");
        assertThat(r.str("$.view")).isEqualTo(view);
        assertThat(r.at("$.draft")).isNull();
        assertThat(r.at("$.preview")).isNull();
        assertThat(r.issues()).isEmpty();
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void extraWordsAfterViewCommandAreIgnored() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/owe @Ken 多少");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.view")).isEqualTo("debts");
        assertThat(r.issues()).isEmpty();
    }
}
