package com.cshlands.api.repayment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
interface RepaymentMapper {
    @Select("SELECT from_member_id, to_member_id, cleared_currency AS currency, status, " +
            "SUM(cleared_amount_minor) AS amount_minor " +
            "FROM repayment " +
            "WHERE trip_id = #{tripId} AND deleted_at IS NULL " +
            "GROUP BY from_member_id, to_member_id, cleared_currency, status")
    List<RepaymentSumRow> getRepaymentSum(@Param("tripId") long tripId);
}
