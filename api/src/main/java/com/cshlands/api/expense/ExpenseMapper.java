package com.cshlands.api.expense;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
interface ExpenseMapper {

    /** 和 ExpenseRow 的字段一一对应（record 按构造器参数名取列，少一列就是 null）。不过滤 deleted_at：幂等重放要能看到已删除的 */
    String ROW_SELECT = "SELECT id, trip_id, client_uuid, title, amount_minor, currency, " +
            "       paid_by_member_id, created_by_member_id, split_mode, occurred_at, occurred_tz, " +
            "       category, visibility, source, note, version, created_at, updated_at, deleted_at " +
            "FROM expense";

    @Insert("INSERT INTO expense (trip_id, client_uuid, title, amount_minor, currency, paid_by_member_id, created_by_member_id, split_mode, occurred_at, occurred_tz, category, visibility, source, note) " +
            "VALUES (#{tripId}, #{clientUuid}, #{title}, #{amountMinor}, #{currency}, #{paidByMemberId}, #{createdByMemberId}, #{splitMode}, #{occurredAt}, #{occurredTz}, #{category}, #{visibility}, #{source}, #{note})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(NewExpense newExpense);

    // 写在 xml 文件
    void insertSplits(@Param("newSplits") List<NewSplit> newSplits);

    @Select(ROW_SELECT + " WHERE trip_id = #{tripId} AND client_uuid = #{clientUuid}")
    ExpenseRow findByClientUuid(@Param("tripId") long tripId, @Param("clientUuid") String clientUuid);

    /**
     * 这个成员看得见的、没删除的一笔：共同支出，或者自己记的个人支出。
     * 不存在、已删除、别的行程的、别人的个人支出，一律是 null，调用方都当 404，不泄露它存在过。
     */
    @Select(ROW_SELECT + " WHERE id = #{expenseId} AND trip_id = #{tripId} AND deleted_at IS NULL " +
            "AND (visibility = 'trip' OR created_by_member_id = #{memberId})")
    ExpenseRow findVisible(@Param("tripId") long tripId, @Param("expenseId") long expenseId, @Param("memberId") long memberId);

    @Select("SELECT member_id, amount_minor FROM expense_split " +
            "WHERE expense_id = #{expenseId} ORDER BY member_id")
    List<SplitRow> findSplits(@Param("expenseId") long expenseId);
}
