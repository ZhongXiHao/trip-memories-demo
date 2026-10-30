package com.cshlands.api.expense;

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
import java.util.stream.LongStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 支出创建相关测试的公共工具：用 JDBC 造数据、构造请求体、调用接口、清理。
 * 注解（@SpringBootTest 等）留在各个具体测试类上，这里只放工具。
 * 全部通过 HTTP 接口和 SQL 访问，不引用还没写好的类，所以实现没写完时这些测试也能编译，只是运行时是红的。
 */
abstract class ExpenseTestBase {

    /** 显式指定的大编号，用来构造"userId 恰好等于另一个人的 memberId" */
    protected static final long EXPLICIT_ID = 900_000_007L;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();

    /** 按外键顺序清理：分摊 → 支出 → 成员 → Trip → 用户。只删自己造的 */
    @AfterEach
    void deleteData() {
        for (long userId : userIds) {
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

    // ---- 造数据 ----

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
        jdbc.update("INSERT INTO trip (name, timezone, display_currency, created_by) "
                + "VALUES ('東京', 'Asia/Tokyo', 'HKD', ?)", ownerId);
        long tripId = jdbc.queryForObject("SELECT MAX(id) FROM trip", Long.class);
        member(tripId, ownerId, "owner", "active");
        return tripId;
    }

    protected long tripStartingOn(long ownerId, String startDate) {
        long tripId = trip(ownerId);
        jdbc.update("UPDATE trip SET start_date = ? WHERE id = ?", startDate, tripId);
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

    /** 一个行程，成员按 memberId 升序：me（Owner）、alice、bob、carol，都在籍。user 开头的是 userId，member 开头的是 memberId */
    protected class Scene {
        final long meUser = user("e-me");
        final long tripId = trip(meUser);
        final long meMember = memberId(tripId, meUser);
        final long aliceUser = user("e-alice");
        final long aliceMember = member(tripId, aliceUser, "member", "active");
        final long bobUser = user("e-bob");
        final long bobMember = member(tripId, bobUser, "member", "active");
        final long carolUser = user("e-carol");
        final long carolMember = member(tripId, carolUser, "member", "active");
    }

    /**
     * 直接往库里插一笔支出，不经过创建接口。日元，2026-10-03 11:00 UTC，记录时区 Asia/Tokyo；
     * 个人支出（none）按表的约束自动是 private。返回 id。
     */
    protected long insertExpense(long tripId, long paidBy, long createdBy, String splitMode, long amountMinor) {
        return insertExpense(tripId, paidBy, createdBy, splitMode, amountMinor, "2026-10-03 11:00:00.000", "Asia/Tokyo");
    }

    protected long insertExpense(long tripId, long paidBy, long createdBy, String splitMode, long amountMinor,
                                 String occurredAtUtc, String occurredTz) {
        String visibility = "none".equals(splitMode) ? "private" : "trip";
        jdbc.update("INSERT INTO expense (trip_id, client_uuid, title, amount_minor, currency, paid_by_member_id, "
                        + "created_by_member_id, split_mode, occurred_at, occurred_tz, visibility) "
                        + "VALUES (?, ?, '拉麵', ?, 'JPY', ?, ?, ?, ?, ?, ?)",
                tripId, uuid(), amountMinor, paidBy, createdBy, splitMode, occurredAtUtc, occurredTz, visibility);
        return jdbc.queryForObject("SELECT MAX(id) FROM expense WHERE trip_id = ?", Long.class, tripId);
    }

    protected void insertSplit(long tripId, long expenseId, long memberId, long amountMinor) {
        jdbc.update("INSERT INTO expense_split (trip_id, expense_id, member_id, amount_minor) VALUES (?, ?, ?, ?)",
                tripId, expenseId, memberId, amountMinor);
    }

    // ---- 请求体 ----

    protected static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** 请求体构造器。字段按名字存原始 JSON，with/without 可以增删，方便造"缺字段""错字段"的请求 */
    protected static final class Body {
        private final Map<String, String> fields = new LinkedHashMap<>();

        private Body(String clientUuid, long amountMinor, String currency, long paidBy, String mode) {
            fields.put("clientUuid", quote(clientUuid));
            fields.put("title", quote("拉麵"));
            fields.put("amountMinor", String.valueOf(amountMinor));
            fields.put("currency", quote(currency));
            fields.put("paidByMemberId", String.valueOf(paidBy));
            fields.put("splitMode", quote(mode));
        }

        /** equal：只传成员 */
        static Body equal(String clientUuid, long amountMinor, String currency, long paidBy, long... members) {
            return new Body(clientUuid, amountMinor, currency, paidBy, "equal")
                    .raw("splits", LongStream.of(members)
                            .mapToObj(id -> "{\"memberId\":" + id + "}")
                            .collect(Collectors.joining(",", "[", "]")));
        }

        /** custom：参数依次是 memberId1, amount1, memberId2, amount2, ... */
        static Body custom(String clientUuid, long amountMinor, String currency, long paidBy, long... memberAndAmount) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < memberAndAmount.length; i += 2) {
                items.add("{\"memberId\":" + memberAndAmount[i] + ",\"amountMinor\":" + memberAndAmount[i + 1] + "}");
            }
            return new Body(clientUuid, amountMinor, currency, paidBy, "custom")
                    .raw("splits", "[" + String.join(",", items) + "]");
        }

        /** none：个人支出，不带 splits */
        static Body personal(String clientUuid, long amountMinor, String currency, long paidBy) {
            return new Body(clientUuid, amountMinor, currency, paidBy, "none");
        }

        Body str(String field, String value) {
            fields.put(field, quote(value));
            return this;
        }

        Body num(String field, long value) {
            fields.put(field, String.valueOf(value));
            return this;
        }

        Body raw(String field, String rawJson) {
            fields.put(field, rawJson);
            return this;
        }

        Body without(String field) {
            fields.remove(field);
            return this;
        }

        String uuid() {
            String quoted = fields.get("clientUuid");
            return quoted == null ? null : quoted.substring(1, quoted.length() - 1);
        }

        String json() {
            return fields.entrySet().stream()
                    .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                    .collect(Collectors.joining(",", "{", "}"));
        }

        private static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    // ---- 调用和查询 ----

    protected ResultActions create(long actingUserId, long tripId, Body body) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/expenses")
                .header("X-User-Id", actingUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.json()));
    }

    /** 修改一笔支出（整体替换） */
    protected ResultActions update(long actingUserId, long tripId, long expenseId, Body body) throws Exception {
        return mockMvc.perform(put("/trips/" + tripId + "/expenses/" + expenseId)
                .header("X-User-Id", actingUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.json()));
    }

    /** 支出列表。query 是不带问号的查询串，比如 "date=2026-10-03&memberId=3"，没有就传空串 */
    protected ResultActions list(long actingUserId, long tripId, String query) throws Exception {
        String url = "/trips/" + tripId + "/expenses" + (query.isEmpty() ? "" : "?" + query);
        return mockMvc.perform(get(url).header("X-User-Id", actingUserId));
    }

    /** 记账预览 */
    protected ResultActions preview(long actingUserId, long tripId, Body body) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/expenses/preview")
                .header("X-User-Id", actingUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.json()));
    }

    /** 软删除一笔支出（契约里用 POST） */
    protected ResultActions deleteExpense(long actingUserId, long tripId, long expenseId) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/expenses/" + expenseId + "/delete")
                .header("X-User-Id", actingUserId));
    }

    /** 取一笔支出的详情。方法名不叫 get，避免盖住静态导入的 MockMvcRequestBuilders.get */
    protected ResultActions fetch(long actingUserId, long tripId, long expenseId) throws Exception {
        return mockMvc.perform(get("/trips/" + tripId + "/expenses/" + expenseId)
                .header("X-User-Id", actingUserId));
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

    protected static long idOf(String responseJson) {
        return ((Number) JsonPath.read(responseJson, "$.id")).longValue();
    }

    protected static String codeOf(String responseJson) {
        return JsonPath.read(responseJson, "$.code");
    }

    /** 含已软删除的 */
    protected int expenseRows(long tripId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE trip_id = ?", Integer.class, tripId);
    }

    protected int splitRows(long tripId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM expense_split WHERE trip_id = ?", Integer.class, tripId);
    }

    /** 这笔支出在库里的分摊：memberId → 金额 */
    protected Map<Long, Long> splitsOf(long expenseId) {
        Map<Long, Long> splits = new LinkedHashMap<>();
        jdbc.query("SELECT member_id, amount_minor FROM expense_split WHERE expense_id = ? ORDER BY member_id",
                rs -> {
                    splits.put(rs.getLong("member_id"), rs.getLong("amount_minor"));
                }, expenseId);
        return splits;
    }

    protected int versionOf(long expenseId) {
        return jdbc.queryForObject("SELECT version FROM expense WHERE id = ?", Integer.class, expenseId);
    }

    protected String titleOf(long expenseId) {
        return jdbc.queryForObject("SELECT title FROM expense WHERE id = ?", String.class, expenseId);
    }

}
