package com.cshlands.api.member;

import com.cshlands.api.MySqlTestContainerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 转让 Owner 的两步（降级旧 Owner、升级新 Owner）必须在同一个事务里：
 * 第二步抛异常时，第一步要回滚，否则这个 Trip 就没有 Owner 了。
 * 用 spy 让第二步失败来验证；单独一个类是因为 spy 会让 Spring 另建一个上下文。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MySqlTestContainerConfig.class)
class MemberTransferRollbackTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    MemberMapper memberMapper;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void deleteData() {
        for (long userId : userIds) {
            jdbc.update("DELETE FROM trip_member WHERE user_id = ?", userId);
        }
        for (long userId : userIds) {
            jdbc.update("DELETE FROM trip WHERE created_by = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    private long user(String nickname) {
        jdbc.update("INSERT INTO app_user (nickname) VALUES (?)", nickname);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM app_user", Long.class);
        userIds.add(id);
        return id;
    }

    @Test
    void failureWhilePromotingRollsBackTheDemotion() {
        long owner = user("r-owner");
        long target = user("r-target");
        jdbc.update("INSERT INTO trip (name, timezone, display_currency, created_by) "
                + "VALUES ('東京', 'Asia/Tokyo', 'HKD', ?)", owner);
        long tripId = jdbc.queryForObject("SELECT MAX(id) FROM trip", Long.class);
        jdbc.update("INSERT INTO trip_member (trip_id, user_id, role, status) VALUES (?, ?, 'owner', 'active')",
                tripId, owner);
        jdbc.update("INSERT INTO trip_member (trip_id, user_id, role, status) VALUES (?, ?, 'member', 'active')",
                tripId, target);
        long targetMemberId = jdbc.queryForObject("SELECT id FROM trip_member WHERE trip_id = ? AND user_id = ?",
                Long.class, tripId, target);

        doThrow(new IllegalStateException("boom")).when(memberMapper).promoteToOwner(anyLong(), anyLong());

        assertThatThrownBy(() -> mockMvc.perform(
                post("/trips/" + tripId + "/owner/transfer/" + targetMemberId).header("X-User-Id", owner)))
                .hasRootCauseMessage("boom");

        assertThat(jdbc.queryForObject("SELECT role FROM trip_member WHERE trip_id = ? AND user_id = ?",
                String.class, tripId, owner)).isEqualTo("owner");
        assertThat(jdbc.queryForObject("SELECT role FROM trip_member WHERE trip_id = ? AND user_id = ?",
                String.class, tripId, target)).isEqualTo("member");
    }

}
