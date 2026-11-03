package com.cshlands.api.repayment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /**
     * 收款方确认一条：只改 pending 且没取消的，version 加一。
     * 影响 0 行不一定是失败（可能早就确认过了），要回头读出来再判断原因。
     */
    @Update("UPDATE repayment SET status = 'confirmed', confirmed_at = CURRENT_TIMESTAMP(3), version = version + 1 " +
            "WHERE trip_id = #{tripId} AND id = #{repaymentId} AND to_member_id = #{toMemberId} " +
            "AND status = 'pending' AND deleted_at IS NULL")
    int confirm(@Param("tripId") long tripId, @Param("repaymentId") long repaymentId,
                @Param("toMemberId") long toMemberId);

    /** 收款方确认整组：只改该组里 pending 且没取消的行，其余行不动，也不会因此失败 */
    @Update("UPDATE repayment SET status = 'confirmed', confirmed_at = CURRENT_TIMESTAMP(3), version = version + 1 " +
            "WHERE trip_id = #{tripId} AND settlement_group_id = #{settlementGroupId} AND to_member_id = #{toMemberId} " +
            "AND status = 'pending' AND deleted_at IS NULL")
    int confirmGroup(@Param("tripId") long tripId, @Param("settlementGroupId") String settlementGroupId,
                     @Param("toMemberId") long toMemberId);

    /**
     * 付款方或收款方取消一条：只改 pending 且没取消的，version 加一。取消 = 设置 deleted_at。
     * 影响 0 行不一定是失败（可能已经取消过了，或者已经被确认），要回头读出来再判断原因。
     */
    @Update("UPDATE repayment SET deleted_at = CURRENT_TIMESTAMP(3), version = version + 1 " +
            "WHERE trip_id = #{tripId} AND id = #{repaymentId} " +
            "AND (from_member_id = #{memberId} OR to_member_id = #{memberId}) " +
            "AND status = 'pending' AND deleted_at IS NULL")
    int cancel(@Param("tripId") long tripId, @Param("repaymentId") long repaymentId,
               @Param("memberId") long memberId);

    /** 付款方或收款方取消整组：只改该组里 pending 且没取消的行，已确认的行保留，不会因此失败 */
    @Update("UPDATE repayment SET deleted_at = CURRENT_TIMESTAMP(3), version = version + 1 " +
            "WHERE trip_id = #{tripId} AND settlement_group_id = #{settlementGroupId} " +
            "AND (from_member_id = #{memberId} OR to_member_id = #{memberId}) " +
            "AND status = 'pending' AND deleted_at IS NULL")
    int cancelGroup(@Param("tripId") long tripId, @Param("settlementGroupId") String settlementGroupId,
                    @Param("memberId") long memberId);

    // 下面几个写在 RepaymentMapper.xml

    /** 这个行程里的一条还款，含已取消的；不在这个行程里、不存在返回 null */
    RepaymentRow findById(@Param("tripId") long tripId, @Param("repaymentId") long repaymentId);

    List<RepaymentRow> findByGroup(@Param("tripId") long tripId, @Param("settlementGroupId") String settlementGroupId);

    void insertAll(@Param("rows") List<NewRepayment> rows);

    List<RepaymentRow> list(RepaymentListQuery query);
}
