package com.cshlands.api.expense;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

}
