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

    /** 按 (用户, Trip) 取成员对象，不论是否在籍；Trip 已删除时返回 null */
    @Select("SELECT m.id AS member_id, u.id AS user_id, u.nickname, m.role, m.status, m.joined_at " +
            "FROM trip_member m " +
            "JOIN trip t ON t.id = m.trip_id AND t.deleted_at IS NULL " +
            "JOIN app_user u ON u.id = m.user_id " +
            "WHERE m.trip_id = #{tripId} AND m.user_id = #{userId}")
    MemberView findByUser(@Param("userId") long userId, @Param("tripId") long tripId);
}
