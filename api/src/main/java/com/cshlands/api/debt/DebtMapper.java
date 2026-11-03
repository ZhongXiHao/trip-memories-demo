package com.cshlands.api.debt;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
interface DebtMapper {
    @Select("SELECT member_id AS from_member_id, paid_by_member_id AS to_member_id, " +
            "currency, SUM(amount_minor) AS amount_minor " +
            "FROM v_debt_entry WHERE trip_id = #{tripId} " +
            "GROUP BY member_id, paid_by_member_id, currency")
    List<DebtRow> getDebts(@Param("tripId") long tripId);
}
