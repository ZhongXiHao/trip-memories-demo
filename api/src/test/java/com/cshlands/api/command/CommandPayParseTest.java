package com.cshlands.api.command;

import com.cshlands.api.MySqlTestContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /trips/{tripId}/commands/parse 的 `/pay`（严格模式）。行程、成员用 JDBC 造，MockMvc 路径没有 /api。
 * 规则见契约「规则指令」一节；重构解析器时，这里的行为都不能变。
 * issues、warnings 写成 "CODE:text"，顺序不做要求（契约没规定）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class CommandPayParseTest extends CommandTestBase {

    // ---- 成功：契约里的例子和响应结构 ----

    @Test
    void contractExampleProducesDraftAndPreview() throws Exception {
        Scene s = new Scene();

        // 1200 円 ÷ 3 = 400 円，整除。付款人默认是自己，自己自动加入分摊，不提示
        Parsed r = parse(s, "/pay 1200jpy 拉麵 @Ken @Amy");

        assertThat(r.topLevelKeys()).containsExactlyInAnyOrder(
                "status", "command", "kind", "draft", "preview", "view", "issues", "warnings");
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.command")).isEqualTo("pay");
        assertThat(r.str("$.kind")).isEqualTo("expense");
        assertThat(r.at("$.view")).isNull();
        assertThat(r.issues()).isEmpty();
        assertThat(r.warnings()).isEmpty();

        assertThat(r.keysOf("$.draft")).containsExactlyInAnyOrder("title", "amountMinor", "currency",
                "paidByMemberId", "splitMode", "splits", "occurredAt", "occurredTz", "source");
        assertThat(r.str("$.draft.title")).isEqualTo("拉麵");
        assertThat(r.num("$.draft.amountMinor")).isEqualTo(120000L);
        assertThat(r.str("$.draft.currency")).isEqualTo("JPY");
        assertThat(r.num("$.draft.paidByMemberId")).isEqualTo(s.me);
        assertThat(r.str("$.draft.splitMode")).isEqualTo("equal");
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken, s.amy);
        assertThat(r.at("$.draft.splits[0].amountMinor")).as("equal 的 draft 只传成员").isNull();
        assertThat(r.str("$.draft.source")).isEqualTo("command");
        assertThat(r.at("$.draft.clientUuid")).isNull();

        assertThat(r.num("$.preview.amountMinor")).isEqualTo(120000L);
        assertThat(r.str("$.preview.currency")).isEqualTo("JPY");
        assertThat(r.num("$.preview.splits[0].memberId")).isEqualTo(s.me);
        assertThat(r.num("$.preview.splits[0].amountMinor")).isEqualTo(40000L);
        assertThat(r.num("$.preview.splits[1].amountMinor")).isEqualTo(40000L);
        assertThat(r.num("$.preview.splits[2].amountMinor")).isEqualTo(40000L);
        assertThat(r.at("$.preview.warnings")).isEqualTo(List.of());
    }

    @Test
    void previewGivesRemainderToPayer() throws Exception {
        Scene s = new Scene();

        // 1000 円 ÷ 3 = 333 円余 1 円，余数给付款人 Me（和记账预览同一套规则）
        Parsed r = parse(s, "/pay 1000jpy 拉麵 @Ken @Amy");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.num("$.preview.splits[0].memberId")).isEqualTo(s.me);
        assertThat(r.num("$.preview.splits[0].amountMinor")).isEqualTo(33400L);
        assertThat(r.num("$.preview.splits[0].remainderMinor")).isEqualTo(100L);
        assertThat(r.num("$.preview.splits[1].amountMinor")).isEqualTo(33300L);
        assertThat(r.at("$.preview.splits[1].remainderMinor")).isNull();
        assertThat(r.num("$.preview.splits[2].amountMinor")).isEqualTo(33300L);
    }

    @Test
    void draftCanBeSentToCreateExpenseAsIs() throws Exception {
        Scene s = new Scene();
        Parsed r = parse(s, "/pay 1000jpy 拉麵 @Ken @Amy");
        assertThat(expenseRows(s.tripId)).as("解析只算不写").isZero();

        // 前端拿 draft 原样调创建，只补 clientUuid
        Map<String, Object> draft = JsonPath.read(r.json(), "$.draft");
        draft.put("clientUuid", UUID.randomUUID().toString());
        String body = JsonMapper.builder().build().writeValueAsString(draft);

        mockMvc.perform(post("/trips/" + s.tripId + "/expenses")
                        .header("X-User-Id", s.meUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("拉麵"))
                .andExpect(jsonPath("$.amountMinor").value(100000))
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.paidByMemberId").value(s.me))
                .andExpect(jsonPath("$.source").value("command"))
                .andExpect(jsonPath("$.splitMode").value("equal"))
                .andExpect(jsonPath("$.splits[0].memberId").value(s.me))
                .andExpect(jsonPath("$.splits[0].amountMinor").value(33400))
                .andExpect(jsonPath("$.occurredTz").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.category").value("other"));
    }

    // ---- 成功：参数顺序、标题 ----

    @Test
    void argumentsCanComeInAnyOrderAndSplitsAreSortedByMemberId() throws Exception {
        Scene s = new Scene();

        // 先 @Amy 后 @Ken，金额在最后：splits 仍按 memberId 升序 [Me, Ken, Amy]
        Parsed r = parse(s, "/pay @Amy 拉麵 @Ken 1200jpy");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.draft.title")).isEqualTo("拉麵");
        assertThat(r.num("$.draft.amountMinor")).isEqualTo(120000L);
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken, s.amy);
    }

    @Test
    void leftoverWordsJoinedWithSingleSpaceBecomeTitle() throws Exception {
        Scene s = new Scene();

        // 金额、@ 夹在中间；连续两个空格也只留一个
        Parsed r = parse(s, "/pay 一蘭  拉麵 1200jpy @Ken 本店");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.draft.title")).isEqualTo("一蘭 拉麵 本店");
    }

    @Test
    void leadingAndTrailingWhitespaceIsIgnored() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "  /pay 1200jpy 拉麵 @Ken  ");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.draft.title")).isEqualTo("拉麵");
    }

    @Test
    void dateCategoryAndDayWordsStayInTitleInStrictMode() throws Exception {
        Scene s = new Scene();

        // 严格模式不认日期、第几天、类别：原样留在标题里，draft 不带时间和类别（创建时取默认值）
        Parsed r = parse(s, "/pay 1200jpy D3 晚餐 昨天 food @Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.draft.title")).isEqualTo("D3 晚餐 昨天 food");
        assertThat(r.at("$.draft.occurredAt")).isNull();
        assertThat(r.at("$.draft.occurredTz")).isNull();
        assertThat(r.at("$.draft.category")).isNull();
    }

    @Test
    void titleOf128CharactersIsAccepted() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy " + "あ".repeat(128));

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.draft.title")).hasSize(128);
    }

    // ---- 成功：指令名 ----

    @ParameterizedTest
    @ValueSource(strings = {"/pay", "/PAY", "/Pay", "/记账", "/記帳"})
    void payAliasesAndCaseAreNormalized(String command) throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, command + " 1200jpy 拉麵 @Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.command")).isEqualTo("pay");
        assertThat(r.str("$.kind")).isEqualTo("expense");
    }

    @Test
    void fullWidthSlashAtSignAndSpaceWorkLikeHalfWidth() throws Exception {
        Scene s = new Scene();

        // 中文输入法常打出全角的 ／ ＠ 和全角空格
        Parsed r = parse(s, "／pay　1200jpy　拉麵　＠Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.command")).isEqualTo("pay");
        assertThat(r.str("$.draft.title")).isEqualTo("拉麵");
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken);
    }

    // ---- 成功：金额和币别 ----

    @ParameterizedTest
    @ValueSource(strings = {"1200jpy", "1200JPY", "1200Jpy", "jpy1200", "JPY1200", "1,200jpy", "JPY1,200"})
    void amountWithCurrencyCodeOnEitherSide(String amount) throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay " + amount + " 拉麵 @Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.num("$.draft.amountMinor")).isEqualTo(120000L);
        assertThat(r.str("$.draft.currency")).isEqualTo("JPY");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void thousandSeparatorsAndTwoDecimals() throws Exception {
        Scene s = new Scene();

        // USD 1,234.50 → 123450；÷ 2 = 61725，整除
        Parsed r = parse(s, "/pay 1,234.50usd 晚餐 @Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.num("$.draft.amountMinor")).isEqualTo(123450L);
        assertThat(r.str("$.draft.currency")).isEqualTo("USD");
        assertThat(r.num("$.preview.splits[0].amountMinor")).isEqualTo(61725L);
    }

    @Test
    void bareNumberUsesTripDefaultCurrency() throws Exception {
        Scene s = new Scene();
        // 默认币别改成 KRW，证明取的是行程的 defaultCurrency，不是写死的
        setDefaultCurrency(s.tripId, "KRW");

        Parsed r = parse(s, "/pay 1200 拉麵 @Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.num("$.draft.amountMinor")).isEqualTo(120000L);
        assertThat(r.str("$.draft.currency")).isEqualTo("KRW");
        assertThat(r.warnings()).containsExactly("CURRENCY_DEFAULTED:1200");
    }

    @Test
    void amountSeparatedFromCurrencyCodeIsBareNumberAndCodeGoesToTitle() throws Exception {
        Scene s = new Scene();
        setDefaultCurrency(s.tripId, "HKD");

        // 契约要求数字和币别码紧挨。分开写时数字按默认币别算，"jpy" 留在标题里，靠 CURRENCY_DEFAULTED 提醒用户
        Parsed r = parse(s, "/pay 1200 jpy 拉麵 @Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.draft.currency")).isEqualTo("HKD");
        assertThat(r.str("$.draft.title")).isEqualTo("jpy 拉麵");
        assertThat(r.warnings()).containsExactly("CURRENCY_DEFAULTED:1200");
    }

    @Test
    void wordThatOnlyContainsDigitsIsNotAnAmount() throws Exception {
        Scene s = new Scene();

        // "7-11" 不是金额的形状，整个词留在标题里
        Parsed r = parse(s, "/pay 1200jpy 7-11 便利店 @Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.num("$.draft.amountMinor")).isEqualTo(120000L);
        assertThat(r.str("$.draft.title")).isEqualTo("7-11 便利店");
    }

    // ---- 成功：分摊者 ----

    @Test
    void noMentionSplitsAmongAllActiveMembers() throws Exception {
        Scene s = new Scene();

        // 在籍 4 人（Leo 已退出，不算）：1200 ÷ 4 = 300 円
        Parsed r = parse(s, "/pay 1200jpy 拉麵");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken, s.amy, s.bob);
        assertThat(r.num("$.preview.splits[3].amountMinor")).isEqualTo(30000L);
        assertThat(r.warnings()).containsExactly("SPLIT_ALL_MEMBERS:null");
    }

    @Test
    void mentioningMyselfExplicitlyNeedsNoWarning() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 @Me @Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken);
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void nicknameMatchIgnoresCase() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 @kEN");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken);
    }

    @Test
    void sameMemberMentionedTwiceCountsOnce() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 @Ken @ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken);
    }

    @Test
    void mentionsWrittenTogetherAreSplitAtAtSign() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 @Ken@Amy");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken, s.amy);
    }

    @Test
    void atSignInsideAWordIsTitleText() throws Exception {
        Scene s = new Scene();

        // 只有以 @ 开头的词才是点名
        Parsed r = parse(s, "/pay 1200jpy a@b");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.str("$.draft.title")).isEqualTo("a@b");
        assertThat(r.warnings()).containsExactly("SPLIT_ALL_MEMBERS:null");
    }

    @Test
    void activeMemberWinsOverLeftMemberWithSameNickname() throws Exception {
        Scene s = new Scene();
        long activeLeo = activeMember(s.tripId, "LEO");

        Parsed r = parse(s, "/pay 1200jpy 拉麵 @leo");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.draftSplitMembers()).containsExactly(s.me, activeLeo);
    }

    // ---- 成功：付款人 ----

    @ParameterizedTest
    @ValueSource(strings = {"by @Ken", "BY @Ken", "由 @Ken", "由@Ken"})
    void payerCanBeSomeoneElseAndIsAddedToSplit(String payer) throws Exception {
        Scene s = new Scene();

        // Ken 付钱，分给 Amy 和 Ken 自己；Me 没被点名，不在分摊里
        Parsed r = parse(s, "/pay 1200jpy 拉麵 " + payer + " @Amy");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.num("$.draft.paidByMemberId")).isEqualTo(s.ken);
        assertThat(r.draftSplitMembers()).containsExactly(s.ken, s.amy);
        assertThat(r.warnings()).containsExactly("PAYER_ADDED_TO_SPLIT:@Ken");
        assertThat(r.str("$.draft.title")).isEqualTo("拉麵");
    }

    @ParameterizedTest
    @ValueSource(strings = {"by @Ken@Amy", "由 @Ken@Amy", "由@Ken@Amy"})
    void onlyFirstMentionAfterPayerMarkerIsThePayer(String payerAndMention) throws Exception {
        Scene s = new Scene();

        // 连写和分开写（by @Ken @Amy）一样：Ken 付钱，Amy 是普通的点名
        Parsed r = parse(s, "/pay 1200jpy 拉麵 " + payerAndMention);

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.num("$.draft.paidByMemberId")).isEqualTo(s.ken);
        assertThat(r.draftSplitMembers()).containsExactly(s.ken, s.amy);
        assertThat(r.warnings()).containsExactly("PAYER_ADDED_TO_SPLIT:@Ken");
        assertThat(r.str("$.draft.title")).isEqualTo("拉麵");
    }

    @Test
    void payerAlsoMentionedForSplitNeedsNoWarning() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 by @Ken @Ken @Amy");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.num("$.draft.paidByMemberId")).isEqualTo(s.ken);
        assertThat(r.draftSplitMembers()).containsExactly(s.ken, s.amy);
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void payerWithoutOtherMentionsSplitsAmongAll() throws Exception {
        Scene s = new Scene();

        // 只有 by @Ken：没有别的 @，全体均分；Ken 本来就在全体里，不提示 PAYER_ADDED_TO_SPLIT
        Parsed r = parse(s, "/pay 1200jpy 拉麵 by @Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.num("$.draft.paidByMemberId")).isEqualTo(s.ken);
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken, s.amy, s.bob);
        assertThat(r.warnings()).containsExactly("SPLIT_ALL_MEMBERS:null");
    }

    @Test
    void byOrYouWithoutMentionIsTitleText() throws Exception {
        Scene s = new Scene();

        // "by bus"、"由布院" 后面不是 @，只是普通文字
        Parsed r = parse(s, "/pay 1200jpy 由布院 by bus @Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.num("$.draft.paidByMemberId")).isEqualTo(s.me);
        assertThat(r.str("$.draft.title")).isEqualTo("由布院 by bus");
    }

    @Test
    void memberIdIsNotConfusedWithUserId() throws Exception {
        // 防错位：Ken 的 memberId 恰好等于我的 userId。实现若拿 userId 当付款人，付款人就成了 Ken
        long meUser = userWithId(EXPLICIT_ID, "Me");
        long tripId = tripWithoutMembers(meUser);
        long me = memberWithId(EXPLICIT_ID + 1, tripId, meUser, "owner", "active");
        long ken = memberWithId(EXPLICIT_ID, tripId, user("Ken"), "member", "active");

        Parsed r = parse(meUser, tripId, "/pay 1200jpy 拉麵 @Ken");

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.num("$.draft.paidByMemberId")).isEqualTo(me);
        assertThat(r.draftSplitMembers()).containsExactly(ken, me);
    }

    // ---- 失败：金额 ----

    @Test
    void missingAmountKeepsRecognizedPartsInDraft() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 拉麵 @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.str("$.command")).isEqualTo("pay");
        assertThat(r.str("$.kind")).isEqualTo("expense");
        assertThat(r.issues()).containsExactly("MISSING_AMOUNT:null");
        assertThat(r.at("$.preview")).isNull();
        // draft 是已识别的部分，给「改用表单」带入
        assertThat(r.str("$.draft.title")).isEqualTo("拉麵");
        assertThat(r.at("$.draft.amountMinor")).isNull();
        assertThat(r.at("$.draft.currency")).isNull();
        assertThat(r.num("$.draft.paidByMemberId")).isEqualTo(s.me);
        assertThat(r.str("$.draft.splitMode")).isEqualTo("equal");
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken);
        assertThat(r.str("$.draft.source")).isEqualTo("command");
        assertThat(r.warnings()).as("没有金额就没有币别，不提示 CURRENCY_DEFAULTED").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"拉麵1200jpy", "12,00jpy"})
    void wordThatIsNotExactlyAnAmountLeavesAmountMissing(String word) throws Exception {
        Scene s = new Scene();

        // 金额要整个词都是金额；千分位要三位一组
        Parsed r = parse(s, "/pay " + word + " @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("MISSING_AMOUNT:null");
        assertThat(r.str("$.draft.title")).isEqualTo(word);
    }

    @Test
    void twoAmountsAreAmbiguous() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 300jpy @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactlyInAnyOrder("AMBIGUOUS_AMOUNT:1200jpy", "AMBIGUOUS_AMOUNT:300jpy");
        assertThat(r.at("$.draft.amountMinor")).isNull();
        assertThat(r.at("$.preview")).isNull();
    }

    @Test
    void bareNumberCountsAsSecondAmount() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 2 @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactlyInAnyOrder("AMBIGUOUS_AMOUNT:1200jpy", "AMBIGUOUS_AMOUNT:2");
    }

    @Test
    void symbolAmountCountsAsAmountWhenThereAreTwo() throws Exception {
        Scene s = new Scene();

        // 两个金额时只报 AMBIGUOUS_AMOUNT，不再另报 ¥300 的 UNKNOWN_CURRENCY
        Parsed r = parse(s, "/pay 1200jpy ¥300 拉麵 @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactlyInAnyOrder("AMBIGUOUS_AMOUNT:1200jpy", "AMBIGUOUS_AMOUNT:¥300");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1200abc", "abc1200", "¥1200", "￥1200", "1200円", "1200元", "$12", "€12"})
    void unknownCodeOrCurrencySymbolIsUnknownCurrency(String amount) throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay " + amount + " 拉麵 @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("UNKNOWN_CURRENCY:" + amount);
        assertThat(r.at("$.draft.amountMinor")).isNull();
        assertThat(r.str("$.draft.title")).isEqualTo("拉麵");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.5jpy", "1250.50krw", "1.234usd"})
    void amountThatIsNotAMultipleOfRoundingUnitIsNotRoundable(String amount) throws Exception {
        Scene s = new Scene();

        // JPY、KRW 只到整元；超过两位小数的任何币别都不能取整
        Parsed r = parse(s, "/pay " + amount + " 拉麵 @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("AMOUNT_NOT_ROUNDABLE:" + amount);
        assertThat(r.at("$.preview")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0jpy", "0.00usd", "99999999999999999999jpy"})
    void zeroOrOverflowingAmountIsInvalid(String amount) throws Exception {
        Scene s = new Scene();

        // 金额要 > 0；超出 long 的不能变成 500
        Parsed r = parse(s, "/pay " + amount + " 拉麵 @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("INVALID_AMOUNT:" + amount);
    }

    // ---- 失败：标题 ----

    @Test
    void missingTitle() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("MISSING_TITLE:null");
        assertThat(r.at("$.draft.title")).isNull();
        assertThat(r.num("$.draft.amountMinor")).isEqualTo(120000L);
        assertThat(r.at("$.preview")).isNull();
    }

    @Test
    void titleLongerThan128CharactersIsRejected() throws Exception {
        Scene s = new Scene();
        String title = "あ".repeat(129);

        Parsed r = parse(s, "/pay 1200jpy " + title);

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("TITLE_TOO_LONG:" + title);
    }

    @Test
    void bareCommandMissesBothAmountAndTitle() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.str("$.command")).isEqualTo("pay");
        assertThat(r.issues()).containsExactlyInAnyOrder("MISSING_AMOUNT:null", "MISSING_TITLE:null");
    }

    // ---- 失败：成员 ----

    @Test
    void unknownMemberIsReportedAndRecognizedMembersStayInDraft() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 @Ken @Nobody");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("UNKNOWN_MEMBER:@Nobody");
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken);
        assertThat(r.num("$.draft.amountMinor")).isEqualTo(120000L);
        assertThat(r.str("$.draft.title")).isEqualTo("拉麵");
        assertThat(r.at("$.preview")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"@", "@Ken，", "@Ken,", "@ Ken"})
    void punctuationIsNotStrippedFromNicknames(String mention) throws Exception {
        Scene s = new Scene();

        // 基线刻意不猜：名字到空白或下一个 @ 为止，标点也算名字的一部分；"@ Ken" 的 @ 后面是空的
        Parsed r = parse(s, "/pay 1200jpy 拉麵 " + mention);

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).contains("UNKNOWN_MEMBER:" + mention.split(" ")[0]);
    }

    @Test
    void memberOfAnotherTripIsUnknown() throws Exception {
        Scene s = new Scene();
        long otherOwner = user("Zed");
        trip(otherOwner);

        Parsed r = parse(s, "/pay 1200jpy 拉麵 @Zed");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("UNKNOWN_MEMBER:@Zed");
    }

    @Test
    void duplicateNicknameIsAmbiguous() throws Exception {
        Scene s = new Scene();
        activeMember(s.tripId, "Sam");
        activeMember(s.tripId, "sam");

        Parsed r = parse(s, "/pay 1200jpy 拉麵 @SAM");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("AMBIGUOUS_MEMBER:@SAM");
    }

    @Test
    void leftMemberCannotShareTheExpense() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 @Leo @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("MEMBER_LEFT:@Leo");
        assertThat(r.draftSplitMembers()).containsExactly(s.me, s.ken);
    }

    @Test
    void leftMemberCannotBePayer() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 by @Leo @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("MEMBER_LEFT:@Leo");
        assertThat(r.at("$.draft.paidByMemberId")).isNull();
    }

    @Test
    void unknownPayerLeavesPayerEmpty() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 by @Nobody @Ken");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactly("UNKNOWN_MEMBER:@Nobody");
        assertThat(r.at("$.draft.paidByMemberId")).isNull();
        // 付款人没认出来，就没有人被加入分摊，不提示 PAYER_ADDED_TO_SPLIT
        assertThat(r.draftSplitMembers()).containsExactly(s.ken);
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void twoPayersAreAmbiguous() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 1200jpy 拉麵 by @Ken by @Amy");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).extracting(issue -> issue.split(":")[0]).containsExactly("AMBIGUOUS_PAYER");
        assertThat(r.at("$.draft.paidByMemberId")).isNull();
    }

    // ---- 失败：几处同时出错 ----

    @Test
    void independentProblemsAreAllReported() throws Exception {
        Scene s = new Scene();

        Parsed r = parse(s, "/pay 拉麵 @Nobody");

        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.issues()).containsExactlyInAnyOrder("MISSING_AMOUNT:null", "UNKNOWN_MEMBER:@Nobody");
    }
}
