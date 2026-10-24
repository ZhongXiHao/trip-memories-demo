package com.cshlands.api.member;

import org.apache.ibatis.annotations.*;

@Mapper
interface MemberMapper {

    @Insert("INSERT INTO trip_member (trip_id, user_id, role, status) " +
            "VALUES (#{tripId}, #{userId}, #{role}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewMember newMember);

    /**
     * 用户在这个 Trip 里的在籍成员身份；不是成员、已退出、Trip 不存在或已删除时返回 null
     */
    @Select("SELECT m.id AS member_id, m.role " +
            "FROM trip_member m " +
            "JOIN trip t ON t.id = m.trip_id AND t.deleted_at IS NULL " +
            "WHERE m.trip_id = #{tripId} AND m.user_id = #{userId} AND m.status = 'active'")
    ActiveMember findActive(@Param("userId") long userId, @Param("tripId") long tripId);

    /** 把已退出的成员改回在籍，沿用原行（和 memberId）。影响 0 行说明没有"已退出"的记录。 */
    @Update("UPDATE trip_member SET status = 'active', joined_at = CURRENT_TIMESTAMP(3) " +
            "WHERE trip_id = #{tripId} AND user_id = #{userId} AND status = 'left'")
    int reactivate(@Param("userId") long userId, @Param("tripId") long tripId);

    /** 成员对象的查询（不论是否在籍）；Trip 已删除的查不到。后面接 WHERE 条件。 */
    String MEMBER_VIEW_SELECT = "SELECT m.id AS member_id, u.id AS user_id, u.nickname, m.role, m.status, m.joined_at " +
            "FROM trip_member m " +
            "JOIN trip t ON t.id = m.trip_id AND t.deleted_at IS NULL " +
            "JOIN app_user u ON u.id = m.user_id " +
            "WHERE m.trip_id = #{tripId}";

    /** 按 (用户, Trip) 取成员对象，不论是否在籍；Trip 已删除时返回 null */
    @Select(MEMBER_VIEW_SELECT + " AND m.user_id = #{userId}")
    MemberView findByUser(@Param("userId") long userId, @Param("tripId") long tripId);

    /** 按 (成员 id, Trip) 取成员对象，不论是否在籍；memberId 不属于这个 Trip 时返回 null */
    @Select(MEMBER_VIEW_SELECT + " AND m.id = #{memberId}")
    MemberView findById(@Param("memberId") long memberId, @Param("tripId") long tripId);

    /** 把在籍的非 Owner 设为已退出（按 userId）；影响 0 行说明不在籍或是 Owner */
    @Update("UPDATE trip_member SET status = 'left' " +
            "WHERE trip_id = #{tripId} AND user_id = #{userId} AND status = 'active' AND role <> 'owner'")
    int markLeft(@Param("userId") long userId, @Param("tripId") long tripId);

    /** 同上，按成员 id；已退出的、Owner、不属于这个 Trip 的都影响 0 行 */
    @Update("UPDATE trip_member SET status = 'left' " +
            "WHERE trip_id = #{tripId} AND id = #{memberId} AND status = 'active' AND role <> 'owner'")
    int markLeftById(@Param("memberId") long memberId, @Param("tripId") long tripId);
}
