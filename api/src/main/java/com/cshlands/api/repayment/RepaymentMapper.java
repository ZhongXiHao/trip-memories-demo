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

    /**
     * 针对某笔支出，fromMemberId 已有的、没取消的还款（pending 和 confirmed）的销帐金额之和。
     * 带 expenseId 的还款币别一定等于这笔支出的币别，所以直接求和；没有时是 0。
     */
    @Select("SELECT COALESCE(SUM(cleared_amount_minor), 0) FROM repayment " +
            "WHERE trip_id = #{tripId} AND expense_id = #{expenseId} AND from_member_id = #{fromMemberId} " +
            "AND deleted_at IS NULL")
    long sumClearedForExpense(@Param("tripId") long tripId, @Param("expenseId") long expenseId,
                              @Param("fromMemberId") long fromMemberId);

    // 下面两个写在 RepaymentMapper.xml

    List<RepaymentRow> findByGroup(@Param("tripId") long tripId, @Param("settlementGroupId") String settlementGroupId);

    void insertAll(@Param("rows") List<NewRepayment> rows);
}
