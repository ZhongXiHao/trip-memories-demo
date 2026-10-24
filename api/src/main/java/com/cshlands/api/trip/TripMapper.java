package com.cshlands.api.trip;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
interface TripMapper {

    /** 当前用户（#{userId}）视角的 Trip：只包含他在籍的、未删除的。created_at 由库的默认值生成，所以插入后要查回来。 */
    String VIEW_SELECT = "SELECT t.id, t.name, t.description, t.start_date, t.end_date, t.timezone, " +
            "       t.display_currency, m.id AS my_member_id, m.role AS my_role, " +
            "       (SELECT COUNT(*) FROM trip_member c WHERE c.trip_id = t.id AND c.status = 'active') AS member_count, " +
            "       t.created_at " +
            "FROM trip t " +
            "JOIN trip_member m ON m.trip_id = t.id AND m.user_id = #{userId} AND m.status = 'active' " +
            "WHERE t.deleted_at IS NULL";

    @Insert("INSERT INTO trip (name, description, start_date, end_date, timezone, display_currency, created_by) " +
            "VALUES (#{name}, #{description}, #{startDate}, #{endDate}, #{timezone}, #{displayCurrency}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewTrip trip);

    /** 不是在籍成员或已删除时返回 null */
    @Select(VIEW_SELECT + " AND t.id = #{tripId}")
    TripView findView(@Param("tripId") long tripId, @Param("userId") long userId);

    /** 我的行程，按创建时间倒序 */
    @Select(VIEW_SELECT + " ORDER BY t.created_at DESC, t.id DESC")
    List<TripView> listViews(@Param("userId") long userId);

    /** 整体替换可编辑字段 */
    @Update("UPDATE trip SET name = #{request.name}, description = #{request.description}, " +
            "start_date = #{request.startDate}, end_date = #{request.endDate}, " +
            "timezone = #{request.timezone}, display_currency = #{request.displayCurrency} " +
            "WHERE id = #{tripId} AND deleted_at IS NULL")
    int update(@Param("tripId") long tripId, @Param("request") TripRequest request);

    @Update("UPDATE trip SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = #{tripId} AND deleted_at IS NULL")
    int softDelete(@Param("tripId") long tripId);

}
