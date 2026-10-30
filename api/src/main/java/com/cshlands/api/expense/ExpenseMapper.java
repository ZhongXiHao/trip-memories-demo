package com.cshlands.api.expense;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
interface ExpenseMapper {


    @Insert("INSERT INTO expense (trip_id, client_uuid, title, amount_minor, currency, paid_by_member_id, created_by_member_id, split_mode, occurred_at, occurred_tz, category, visibility, source, note) " +
            "VALUES (#{tripId}, #{clientUuid}, #{title}, #{amountMinor}, #{currency}, #{paidByMemberId}, #{createdByMemberId}, #{splitMode}, #{occurredAt}, #{occurredTz}, #{category}, #{visibility}, #{source}, #{note})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(NewExpense newExpense);

    // 写在 xml 文件
    void insertSplits(@Param("newSplits") List<NewSplit> newSplits);

    ExpenseRow findByClientUuid(@Param("tripId") long tripId, @Param("clientUuid") String clientUuid);

    /**
     * 这个成员看得见的、没删除的一笔：共同支出，或者自己记的个人支出。
     * 不存在、已删除、别的行程的、别人的个人支出，一律是 null，调用方都当 404，不泄露它存在过。
     */
    ExpenseRow findVisible(@Param("tripId") long tripId, @Param("expenseId") long expenseId, @Param("memberId") long memberId);

    /**
     * 软删除：没删除过，且这个成员是付款人或记账人。返回影响的行数，0 行时要回头查原因（不存在、已删除，还是没权限）。
     * 分摊行不动，算欠款读的是排除了已删除支出的视图。
     */
    @Update("UPDATE expense SET deleted_at = CURRENT_TIMESTAMP(3) " +
            "WHERE id = #{expenseId} AND trip_id = #{tripId} AND deleted_at IS NULL " +
            "AND (paid_by_member_id = #{memberId} OR created_by_member_id = #{memberId})")
    int softDelete(@Param("tripId") long tripId, @Param("expenseId") long expenseId, @Param("memberId") long memberId);

    @Select("SELECT member_id, amount_minor FROM expense_split " +
            "WHERE expense_id = #{expenseId} ORDER BY member_id")
    List<SplitRow> findSplits(@Param("expenseId") long expenseId);
}
