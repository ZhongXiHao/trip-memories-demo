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

}
