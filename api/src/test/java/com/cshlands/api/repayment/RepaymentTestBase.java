package com.cshlands.api.repayment;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 欠款、还款相关测试的公共工具：用 JDBC 造数据（成员、支出、还款）、调用接口、清理。
 * 注解（@SpringBootTest 等）留在各个具体测试类上，这里只放工具。
 * 全部通过 HTTP 接口和 SQL 访问，不引用还没写好的类，所以实现没写完时这些测试也能编译，只是运行时是红的。
 * 欠款测试放在 debt 包、还款测试放在 repayment 包，所以这个基类和里面要用的东西是 public 的。
 */
public abstract class RepaymentTestBase {

    /** 显式指定的大编号，用来构造"userId 恰好等于另一个人的 memberId" */
    protected static final long EXPLICIT_ID = 900_000_007L;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();

    /** 按外键顺序清理：还款 → 分摊 → 支出 → 成员 → Trip → 用户。只删自己造的 */
    @AfterEach
    protected void deleteData() {
        for (long userId : userIds) {
            jdbc.update("DELETE FROM repayment WHERE trip_id IN (SELECT id FROM trip WHERE created_by = ?)", userId);
            jdbc.update("DELETE FROM expense_split WHERE trip_id IN (SELECT id FROM trip WHERE created_by = ?)", userId);
            jdbc.update("DELETE FROM expense WHERE trip_id IN (SELECT id FROM trip WHERE created_by = ?)", userId);
        }
        for (long userId : userIds) {
            jdbc.update("DELETE FROM trip_member WHERE user_id = ?", userId);
        }
        for (long userId : userIds) {
            jdbc.update("DELETE FROM trip WHERE created_by = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    // ---- 造数据：用户、行程、成员 ----

    protected long user(String nickname) {
        jdbc.update("INSERT INTO app_user (nickname) VALUES (?)", nickname);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM app_user", Long.class);
        userIds.add(id);
        return id;
    }

    protected long userWithId(long id, String nickname) {
        jdbc.update("INSERT INTO app_user (id, nickname) VALUES (?, ?)", id, nickname);
        userIds.add(id);
        return id;
    }

    /** 时区 Asia/Tokyo、没有开始日期的行程，创建者是 Owner */
    protected long trip(long ownerId) {
        jdbc.update("INSERT INTO trip (name, timezone, default_currency, created_by) "
                + "VALUES ('東京', 'Asia/Tokyo', 'HKD', ?)", ownerId);
        long tripId = jdbc.queryForObject("SELECT MAX(id) FROM trip", Long.class);
        member(tripId, ownerId, "owner", "active");
        return tripId;
    }

    protected long member(long tripId, long userId, String role, String status) {
        jdbc.update("INSERT INTO trip_member (trip_id, user_id, role, status) VALUES (?, ?, ?, ?)",
                tripId, userId, role, status);
        return memberId(tripId, userId);
    }

    /** 显式指定 memberId，用来构造"某人的 userId 恰好等于另一个人的 memberId" */
    protected long memberWithId(long memberId, long tripId, long userId, String role, String status) {
        jdbc.update("INSERT INTO trip_member (id, trip_id, user_id, role, status) VALUES (?, ?, ?, ?, ?)",
                memberId, tripId, userId, role, status);
        return memberId;
    }

    protected long memberId(long tripId, long userId) {
        return jdbc.queryForObject("SELECT id FROM trip_member WHERE trip_id = ? AND user_id = ?",
                Long.class, tripId, userId);
    }

    protected void leave(long memberId) {
        jdbc.update("UPDATE trip_member SET status = 'left' WHERE id = ?", memberId);
    }

    protected void deleteTrip(long tripId) {
        jdbc.update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", tripId);
    }

    /**
     * 一个行程，成员按 memberId 升序：me（Owner）、alice、bob、carol，都在籍。
     * user 开头的是 userId，member 开头的是 memberId。排序断言依赖这个升序。
     */
    public class Scene {
        public final long meUser = user("r-me");
        public final long tripId = trip(meUser);
        public final long meMember = memberId(tripId, meUser);
        public final long aliceUser = user("r-alice");
        public final long aliceMember = member(tripId, aliceUser, "member", "active");
        public final long bobUser = user("r-bob");
        public final long bobMember = member(tripId, bobUser, "member", "active");
        public final long carolUser = user("r-carol");
        public final long carolMember = member(tripId, carolUser, "member", "active");
    }

    // ---- 造数据：支出 ----

    protected static String uuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * 一笔共同支出（custom），直接写库，不经过接口。总额 = 各分摊之和；付款人的分摊要自己写进去（不写就是没有自己的份）。
     * 参数依次是 memberId1, amount1, memberId2, amount2, ...。时间固定 2026-10-03 11:00 UTC，Asia/Tokyo。返回支出 id。
     */
    protected long expense(long tripId, long paidBy, String currency, long... memberAndAmount) {
        long total = 0;
        for (int i = 1; i < memberAndAmount.length; i += 2) {
            total += memberAndAmount[i];
        }
        jdbc.update("INSERT INTO expense (trip_id, client_uuid, title, amount_minor, currency, paid_by_member_id, "
                        + "created_by_member_id, split_mode, occurred_at, occurred_tz, visibility) "
                        + "VALUES (?, ?, '拉麵', ?, ?, ?, ?, 'custom', '2026-10-03 11:00:00.000', 'Asia/Tokyo', 'trip')",
                tripId, uuid(), total, currency, paidBy, paidBy);
        long expenseId = jdbc.queryForObject("SELECT MAX(id) FROM expense WHERE trip_id = ?", Long.class, tripId);
        for (int i = 0; i < memberAndAmount.length; i += 2) {
            jdbc.update("INSERT INTO expense_split (trip_id, expense_id, member_id, amount_minor) VALUES (?, ?, ?, ?)",
                    tripId, expenseId, memberAndAmount[i], memberAndAmount[i + 1]);
        }
        return expenseId;
    }

    /** 个人支出：不分账，分摊只有付款人自己一行 */
    protected long personalExpense(long tripId, long memberId, String currency, long amountMinor) {
        jdbc.update("INSERT INTO expense (trip_id, client_uuid, title, amount_minor, currency, paid_by_member_id, "
                        + "created_by_member_id, split_mode, occurred_at, occurred_tz, visibility) "
                        + "VALUES (?, ?, '纪念品', ?, ?, ?, ?, 'none', '2026-10-03 11:00:00.000', 'Asia/Tokyo', 'private')",
                tripId, uuid(), amountMinor, currency, memberId, memberId);
        long expenseId = jdbc.queryForObject("SELECT MAX(id) FROM expense WHERE trip_id = ?", Long.class, tripId);
        jdbc.update("INSERT INTO expense_split (trip_id, expense_id, member_id, amount_minor) VALUES (?, ?, ?, ?)",
                tripId, expenseId, memberId, amountMinor);
        return expenseId;
    }

    protected void softDeleteExpense(long expenseId) {
        jdbc.update("UPDATE expense SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", expenseId);
    }

    // ---- 造数据：还款（绕过接口，所以不受额度限制，可以造出"待确认比欠款还多"这类状态） ----

    /** 一条已确认的还款，同币别，自己一个 settlementGroupId。付款方创建 */
    protected long confirmed(long tripId, long from, long to, String currency, long amountMinor) {
        return repayment(tripId, from, to, currency, amountMinor, currency, amountMinor, "confirmed", false, uuid(), null);
    }

    /** 一条待确认的还款，同币别 */
    protected long pending(long tripId, long from, long to, String currency, long amountMinor) {
        return repayment(tripId, from, to, currency, amountMinor, currency, amountMinor, "pending", false, uuid(), null);
    }

    /** 一条已取消的还款：库里是 pending + deleted_at */
    protected long cancelled(long tripId, long from, long to, String currency, long amountMinor) {
        return repayment(tripId, from, to, currency, amountMinor, currency, amountMinor, "pending", true, uuid(), null);
    }

    protected long repayment(long tripId, long from, long to, String clearedCurrency, long clearedAmountMinor,
                             String paidCurrency, long paidAmountMinor, String status, boolean cancelled,
                             String settlementGroupId, Long expenseId) {
        jdbc.update("INSERT INTO repayment (trip_id, from_member_id, to_member_id, cleared_amount_minor, "
                        + "cleared_currency, paid_amount_minor, paid_currency, settlement_group_id, expense_id, "
                        + "status, confirmed_at, created_by_member_id, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, IF(? = 'confirmed', CURRENT_TIMESTAMP(3), NULL), ?, "
                        + "IF(?, CURRENT_TIMESTAMP(3), NULL))",
                tripId, from, to, clearedAmountMinor, clearedCurrency, paidAmountMinor, paidCurrency,
                settlementGroupId, expenseId, status, status, from, cancelled);
        return jdbc.queryForObject("SELECT MAX(id) FROM repayment WHERE trip_id = ?", Long.class, tripId);
    }

    // ---- 欠款接口 ----

    /** 一项欠款：from 欠 to。pendingMinor 没写就是 0 */
    public record Debt(long fromMemberId, long toMemberId, String currency, long amountMinor, long pendingMinor) {
    }

    protected static Debt owes(long from, long to, String currency, long amountMinor) {
        return new Debt(from, to, currency, amountMinor, 0);
    }

    protected static Debt owes(long from, long to, String currency, long amountMinor, long pendingMinor) {
        return new Debt(from, to, currency, amountMinor, pendingMinor);
    }

    /** 欠款总览的原始响应。query 是不带问号的查询串，比如 "memberId=3&simplify=true"，没有就传空串 */
    protected ResultActions debts(long actingUserId, long tripId, String query) throws Exception {
        String url = "/trips/" + tripId + "/debts" + (query.isEmpty() ? "" : "?" + query);
        return mockMvc.perform(get(url).header("X-User-Id", actingUserId));
    }

    /** 欠款总览，要求 200，按响应里的顺序解析成 Debt 列表 */
    protected List<Debt> overview(long actingUserId, long tripId, String query) throws Exception {
        String json = debts(actingUserId, tripId, query)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return parseDebts(json);
    }

    protected static List<Debt> parseDebts(String responseJson) {
        List<Map<String, Object>> items = JsonPath.read(responseJson, "$.items");
        List<Debt> debts = new ArrayList<>();
        for (Map<String, Object> item : items) {
            debts.add(new Debt(
                    ((Number) item.get("fromMemberId")).longValue(),
                    ((Number) item.get("toMemberId")).longValue(),
                    (String) item.get("currency"),
                    ((Number) item.get("amountMinor")).longValue(),
                    ((Number) item.get("pendingMinor")).longValue()));
        }
        return debts;
    }

    /** 一对一净额：当前用户和 memberId 之间 */
    protected ResultActions debtsWith(long actingUserId, long tripId, long memberId) throws Exception {
        return mockMvc.perform(get("/trips/" + tripId + "/debts/" + memberId).header("X-User-Id", actingUserId));
    }

    /** 一对一净额，要求 200，解析成 Debt 列表 */
    protected List<Debt> pairOverview(long actingUserId, long tripId, long memberId) throws Exception {
        String json = debtsWith(actingUserId, tripId, memberId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return parseDebts(json);
    }

    // ---- 还款接口：创建 ----

    /** 一个要结清的欠款项（原始 JSON）：销帐币别、销帐金额、实付金额 */
    protected static String item(String clearedCurrency, long clearedAmountMinor, long paidAmountMinor) {
        return "{\"clearedCurrency\":\"" + clearedCurrency + "\",\"clearedAmountMinor\":" + clearedAmountMinor
                + ",\"paidAmountMinor\":" + paidAmountMinor + "}";
    }

    /** 同币别的项：实付 = 销帐 */
    protected static String item(String currency, long amountMinor) {
        return item(currency, amountMinor, amountMinor);
    }

    /** 创建还款的请求体。字段按名字存原始 JSON，with/without 可以增删，方便造"缺字段""错字段"的请求 */
    public static final class RepaymentBody {
        private final Map<String, String> fields = new LinkedHashMap<>();

        private RepaymentBody() {
        }

        /** items 是 item(...) 造出来的原始 JSON，按传入顺序放 */
        public static RepaymentBody of(String clientUuid, long from, long to, String paidCurrency, String... items) {
            RepaymentBody body = new RepaymentBody();
            body.fields.put("clientUuid", quote(clientUuid));
            body.fields.put("fromMemberId", String.valueOf(from));
            body.fields.put("toMemberId", String.valueOf(to));
            body.fields.put("paidCurrency", quote(paidCurrency));
            body.fields.put("items", "[" + String.join(",", items) + "]");
            return body;
        }

        public RepaymentBody str(String field, String value) {
            fields.put(field, quote(value));
            return this;
        }

        public RepaymentBody num(String field, long value) {
            fields.put(field, String.valueOf(value));
            return this;
        }

        public RepaymentBody raw(String field, String rawJson) {
            fields.put(field, rawJson);
            return this;
        }

        public RepaymentBody items(String... items) {
            fields.put("items", "[" + String.join(",", items) + "]");
            return this;
        }

        public RepaymentBody without(String field) {
            fields.remove(field);
            return this;
        }

        public String uuid() {
            String quoted = fields.get("clientUuid");
            return quoted == null ? null : quoted.substring(1, quoted.length() - 1);
        }

        public String json() {
            return fields.entrySet().stream()
                    .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                    .collect(Collectors.joining(",", "{", "}"));
        }

        private static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    protected ResultActions createRepayment(long actingUserId, long tripId, RepaymentBody body) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/repayments")
                .header("X-User-Id", actingUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.json()));
    }

    // ---- 直接读库里的还款 ----

    /** 库里的一行还款；deleted = deleted_at 非空（接口上是 cancelled） */
    public record RepaymentRow(long id, long fromMemberId, long toMemberId, String clearedCurrency,
                               long clearedAmountMinor, String paidCurrency, long paidAmountMinor,
                               String settlementGroupId, Long expenseId, String status, boolean confirmed,
                               long createdByMemberId, int version, String note, boolean deleted) {
    }

    private static final String ROW_SELECT = "SELECT id, from_member_id, to_member_id, cleared_currency, "
            + "cleared_amount_minor, paid_currency, paid_amount_minor, settlement_group_id, expense_id, status, "
            + "confirmed_at IS NOT NULL AS confirmed, created_by_member_id, version, note, "
            + "deleted_at IS NOT NULL AS deleted FROM repayment ";

    /** 这个行程里的全部还款（含已取消的），按 cleared_currency、id 排序 */
    protected List<RepaymentRow> repaymentRows(long tripId) {
        return jdbc.query(ROW_SELECT + "WHERE trip_id = ? ORDER BY cleared_currency, id", (rs, i) -> new RepaymentRow(
                rs.getLong("id"), rs.getLong("from_member_id"), rs.getLong("to_member_id"),
                rs.getString("cleared_currency"), rs.getLong("cleared_amount_minor"),
                rs.getString("paid_currency"), rs.getLong("paid_amount_minor"),
                rs.getString("settlement_group_id"), (Long) rs.getObject("expense_id"), rs.getString("status"),
                rs.getBoolean("confirmed"), rs.getLong("created_by_member_id"), rs.getInt("version"),
                rs.getString("note"), rs.getBoolean("deleted")), tripId);
    }

    protected List<RepaymentRow> repaymentRows(long tripId, String settlementGroupId) {
        return repaymentRows(tripId).stream().filter(r -> r.settlementGroupId().equals(settlementGroupId)).toList();
    }

    protected int repaymentCount(long tripId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM repayment WHERE trip_id = ?", Integer.class, tripId);
    }

    /** 把这个组里指定币别的那一行标记为已取消（绕过接口） */
    protected void cancelRow(long tripId, String settlementGroupId, String clearedCurrency) {
        jdbc.update("UPDATE repayment SET deleted_at = CURRENT_TIMESTAMP(3) WHERE trip_id = ? "
                + "AND settlement_group_id = ? AND cleared_currency = ?", tripId, settlementGroupId, clearedCurrency);
    }

    protected RepaymentRow rowOf(long tripId, long repaymentId) {
        return repaymentRows(tripId).stream().filter(r -> r.id() == repaymentId).findFirst().orElseThrow();
    }

    /** 绕过接口把创建时间改掉，用来造"列表按创建时间排序"的场景。utc 形如 "2026-10-10 09:00:00.000" */
    protected void setCreatedAt(long repaymentId, String utc) {
        jdbc.update("UPDATE repayment SET created_at = ? WHERE id = ?", utc, repaymentId);
    }

    /** 同一个转账组里的一行（同币别）。cancelled 为 true 时 status 仍是 pending，只多一个 deleted_at */
    protected long groupRow(long tripId, String settlementGroupId, long from, long to, String currency,
                            long amountMinor, String status, boolean cancelled) {
        return repayment(tripId, from, to, currency, amountMinor, currency, amountMinor, status, cancelled,
                settlementGroupId, null);
    }

    // ---- 还款接口：确认、取消、列表 ----

    protected ResultActions confirmRepayment(long actingUserId, long tripId, long repaymentId) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/repayments/" + repaymentId + "/confirm")
                .header("X-User-Id", actingUserId));
    }

    protected ResultActions confirmGroup(long actingUserId, long tripId, String settlementGroupId) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/repayments/groups/" + settlementGroupId + "/confirm")
                .header("X-User-Id", actingUserId));
    }

    protected ResultActions cancelRepayment(long actingUserId, long tripId, long repaymentId) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/repayments/" + repaymentId + "/cancel")
                .header("X-User-Id", actingUserId));
    }

    protected ResultActions cancelGroup(long actingUserId, long tripId, String settlementGroupId) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/repayments/groups/" + settlementGroupId + "/cancel")
                .header("X-User-Id", actingUserId));
    }

    /** 还款列表。query 是不带问号的查询串，比如 "status=pending&memberId=3"，没有就传空串 */
    protected ResultActions listRepayments(long actingUserId, long tripId, String query) throws Exception {
        String url = "/trips/" + tripId + "/repayments" + (query.isEmpty() ? "" : "?" + query);
        return mockMvc.perform(get(url).header("X-User-Id", actingUserId));
    }

    /** 响应里 $.items 的各项按币别归到 status：{"JPY": "confirmed", ...}。不依赖 items 的顺序 */
    protected static Map<String, String> statusByCurrency(String responseJson) {
        List<Map<String, Object>> items = JsonPath.read(responseJson, "$.items");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            result.put((String) item.get("clearedCurrency"), (String) item.get("status"));
        }
        return result;
    }

    /** 列表响应里的 id，按响应顺序 */
    protected static List<Long> idsIn(String responseJson) {
        List<Number> ids = JsonPath.read(responseJson, "$.items[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    // ---- 并发 ----

    /** 让每个 call 各在一个线程里、在同一时刻开始执行，结果按 calls 的顺序返回 */
    protected List<MvcResult> runAll(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : calls) {
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

    /** 让 count 个线程在同一时刻开始执行 call，返回各自的结果 */
    protected List<MvcResult> runConcurrently(int count, Callable<MvcResult> call) throws Exception {
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

    protected static List<Integer> statuses(List<MvcResult> results) {
        return results.stream().map(r -> r.getResponse().getStatus()).toList();
    }

    protected static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    protected static String codeOf(String responseJson) {
        return JsonPath.read(responseJson, "$.code");
    }
}
