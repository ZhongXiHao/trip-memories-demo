package com.cshlands.api.trip;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface TripMapper {

    @Insert("INSERT INTO trip (name, description, start_date, end_date, timezone, display_currency, created_by) " +
            "VALUES (#{name}, #{description}, #{startDate}, #{endDate}, #{timezone}, #{displayCurrency}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewTrip trip);

    /** 当前用户视角的 Trip；不是在籍成员或已删除时返回 null。created_at 由库的默认值生成，所以插入后要查回来。 */
    @Select("SELECT t.id, t.name, t.description, t.start_date, t.end_date, t.timezone, t.display_currency, " +
            "       m.id AS my_member_id, m.role AS my_role, t.created_at " +
            "FROM trip t " +
            "JOIN trip_member m ON m.trip_id = t.id AND m.user_id = #{userId} AND m.status = 'active' " +
            "WHERE t.id = #{tripId} AND t.deleted_at IS NULL")
    TripView findView(@Param("tripId") long tripId, @Param("userId") long userId);

}
