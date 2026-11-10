package com.cshlands.api.command;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 规则指令测试的公共工具：用 JDBC 造数据、调用解析接口、读响应。注解留在各个具体测试类上。
 * 只走 HTTP 和 SQL，不引用解析器的类，所以实现没写完时也能编译，只是运行时是红的。
 */
abstract class CommandTestBase {

    /** 显式指定的大编号，用来构造"某人的 memberId 恰好等于另一个人的 userId" */
    protected static final long EXPLICIT_ID = 900_000_011L;

    /** 读响应时，缺字段读成 null，不抛异常；"字段在不在"由需要的用例单独断言 */
    private static final Configuration LENIENT = Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS);

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

    /** 时区 Asia/Tokyo、默认币别 JPY 的行程，还没有成员 */
    protected long tripWithoutMembers(long createdBy) {
        jdbc.update("INSERT INTO trip (name, timezone, default_currency, created_by) "
                + "VALUES ('東京', 'Asia/Tokyo', 'JPY', ?)", createdBy);
        return jdbc.queryForObject("SELECT MAX(id) FROM trip", Long.class);
    }

    /** 时区 Asia/Tokyo、默认币别 JPY 的行程，创建者是 Owner */
    protected long trip(long ownerId) {
        long tripId = tripWithoutMembers(ownerId);
        member(tripId, ownerId, "owner", "active");
        return tripId;
    }

    protected long member(long tripId, long userId, String role, String status) {
        jdbc.update("INSERT INTO trip_member (trip_id, user_id, role, status) VALUES (?, ?, ?, ?)",
                tripId, userId, role, status);
        return memberId(tripId, userId);
    }

    protected long memberWithId(long memberId, long tripId, long userId, String role, String status) {
        jdbc.update("INSERT INTO trip_member (id, trip_id, user_id, role, status) VALUES (?, ?, ?, ?, ?)",
                memberId, tripId, userId, role, status);
        return memberId;
    }

    protected long memberId(long tripId, long userId) {
        return jdbc.queryForObject("SELECT id FROM trip_member WHERE trip_id = ? AND user_id = ?",
                Long.class, tripId, userId);
    }

    /** 加一个在籍的普通成员，返回 memberId */
    protected long activeMember(long tripId, String nickname) {
        return member(tripId, user(nickname), "member", "active");
    }

    protected void setDefaultCurrency(long tripId, String currency) {
        jdbc.update("UPDATE trip SET default_currency = ? WHERE id = ?", currency, tripId);
    }

    protected int expenseRows(long tripId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE trip_id = ?", Integer.class, tripId);
    }

    /**
     * 一个行程，成员按 memberId 升序：Me（Owner，就是调用者）、Ken、Amy、Bob 在籍，Leo 已退出。
     * 注意 Amy 的 memberId 比 Ken 大：用"先 @Amy 再 @Ken"可以区分"按提及顺序"和"按 memberId 升序"。
     */
    protected class Scene {
        final long meUser = user("Me");
        final long tripId = trip(meUser);
        final long me = memberId(tripId, meUser);
        final long ken = activeMember(tripId, "Ken");
        final long amy = activeMember(tripId, "Amy");
        final long bob = activeMember(tripId, "Bob");
        final long leoUser = user("Leo");
        final long leo = member(tripId, leoUser, "member", "left");
    }

    // ---- 调用 ----

    protected ResultActions parseRaw(long actingUserId, long tripId, String json) throws Exception {
        return mockMvc.perform(post("/trips/" + tripId + "/commands/parse")
                .header("X-User-Id", actingUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    protected ResultActions parseRequest(long actingUserId, long tripId, String input) throws Exception {
        return parseRaw(actingUserId, tripId, "{\"input\":" + quote(input) + "}");
    }

    /** 调用解析接口，要求 HTTP 200（解析失败也是 200），返回响应 */
    protected Parsed parse(long actingUserId, long tripId, String input) throws Exception {
        String json = parseRequest(actingUserId, tripId, input)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Parsed(json);
    }

    protected Parsed parse(Scene s, String input) throws Exception {
        return parse(s.meUser, s.tripId, input);
    }

    protected static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- 读响应 ----

    /** 解析接口的响应。issues、warnings 写成 "CODE:text" 的列表，方便整体比较（text 为 null 时是 "CODE:null"） */
    protected record Parsed(String json) {

        private DocumentContext doc() {
            return JsonPath.using(LENIENT).parse(json);
        }

        Object at(String path) {
            return doc().read(path);
        }

        String str(String path) {
            return doc().read(path);
        }

        Long num(String path) {
            Number n = doc().read(path);
            return n == null ? null : n.longValue();
        }

        String status() {
            return str("$.status");
        }

        List<String> issues() {
            return entries("$.issues");
        }

        List<String> warnings() {
            return entries("$.warnings");
        }

        /** draft.splits 里的 memberId，按响应里的顺序 */
        List<Long> draftSplitMembers() {
            List<Number> ids = doc().read("$.draft.splits[*].memberId");
            return ids.stream().map(Number::longValue).toList();
        }

        /** 响应的顶层字段名 */
        java.util.Set<String> topLevelKeys() {
            return keysOf("$");
        }

        /** 某个对象的字段名（含值为 null 的） */
        @SuppressWarnings("unchecked")
        java.util.Set<String> keysOf(String path) {
            return ((Map<String, Object>) doc().read(path)).keySet();
        }

        private List<String> entries(String path) {
            List<Map<String, Object>> items = doc().read(path);
            return items.stream().map(m -> m.get("code") + ":" + m.get("text")).toList();
        }
    }
}
