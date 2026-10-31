package com.cshlands.api.expense;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.common.ErrorCode;
import com.cshlands.api.common.PageResult;
import com.cshlands.api.common.RegexUtils;
import com.cshlands.api.currency.CurrencyMapper;
import com.cshlands.api.currency.CurrencyView;
import com.cshlands.api.member.ActiveMember;
import com.cshlands.api.member.MemberService;
import com.cshlands.api.trip.TripClock;
import com.cshlands.api.trip.TripService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.cshlands.api.common.ApiException.validationFailed;
import static com.cshlands.api.expense.ExpenseTime.calculateDayIndex;
import static com.cshlands.api.expense.ExpenseTime.parseDate;
import static com.cshlands.api.expense.ExpenseTime.parseOccurredAt;
import static com.cshlands.api.expense.ExpenseTime.parseTz;

@Service
class ExpenseService {
    private final CurrencyMapper currencyMapper;
    private final MemberService memberService;
    private final ExpenseMapper expenseMapper;
    private final ExpenseWriter expenseWriter;
    private final TripService tripService;
    private final ExpenseUpdater expenseUpdater;

    ExpenseService(CurrencyMapper currencyMapper, MemberService memberService, ExpenseMapper expenseMapper, ExpenseWriter expenseWriter, TripService tripService, ExpenseUpdater expenseUpdater) {
        this.currencyMapper = currencyMapper;
        this.memberService = memberService;
        this.expenseMapper = expenseMapper;
        this.expenseWriter = expenseWriter;
        this.tripService = tripService;
        this.expenseUpdater = expenseUpdater;
    }

    /**
     * 只算不写。校验规则与创建相同（币别、取整单位、成员、分摊方式），返回算好的分摊。
     */
    ExpensePreview preview(long userId, long tripId, ExpenseRequest request) {
        ActiveMember me = memberService.requireActiveMember(userId, tripId);
        int roundingUnit = validate(tripId, request, membersAlreadyIn(me, tripId, request.expenseId()));
        List<SplitView> splits = SplitCalculator.split(me.memberId(), request, roundingUnit);
        return new ExpensePreview(request.amountMinor(), request.currency(), splits, List.of());
    }

    ExpenseResult create(long userId, long tripId, ExpenseRequest request) {
        ActiveMember me = memberService.requireActiveMember(userId, tripId);
        String clientUuid = request.clientUuid();

        if (clientUuid == null) {
            throw validationFailed("clientUuid", "must not be null");
        }
        // 校验是否符合 UUID 格式
        if (!RegexUtils.isValidUuid(clientUuid)) {
            throw validationFailed("clientUuid", "must be a valid UUID");
        }

        TripClock tripClock = tripService.getTripClock(userId, tripId);

        // 第二轮只在窄竞态时出现：别的请求刚好抢先写入，write 返回 false，回到开头重新读，走"已有记录"那条路
        for (int attempt = 0; attempt < 2; attempt++) {
            ExpenseRow existing = expenseMapper.findByClientUuid(tripId, clientUuid);
            if (existing != null) {
                checkDuplicateExpense(me, existing, request);
                return new ExpenseResult(toView(existing, tripClock), false);
            }

            int roundingUnit = validate(tripId, request);
            List<SplitView> splits = SplitCalculator.split(me.memberId(), request, roundingUnit);

            // 校验是否为真实带时区：Z 或 +09:00。可选字段，没传就用默认值，不校验
            if (request.occurredAt() != null) {
                parseOccurredAt(request.occurredAt());
            }

            // 校验是否为合法 IANA
            if (request.occurredTz() != null) {
                parseTz(request.occurredTz());
            }

            if (expenseWriter.write(tripId, me.memberId(), tripClock, request, splits)) {
                return new ExpenseResult(toView(expenseMapper.findByClientUuid(tripId, clientUuid), tripClock), true);
            }
        }
        throw new IllegalStateException("Expense with clientUuid " + clientUuid + " could not be created or found");
    }

    /**
     * 支出详情。看不见的（不存在、已删除、别的行程的、别人的个人支出）都是 404，不泄露它存在过。
     */
    ExpenseView get(long userId, long tripId, long expenseId) {
        ActiveMember me = memberService.requireActiveMember(userId, tripId);
        ExpenseRow row = expenseMapper.findVisible(tripId, expenseId, me.memberId());
        if (row == null) {
            throw ApiException.notFound();
        }
        return toView(row, tripService.getTripClock(userId, tripId));
    }

    /**
     * 软删除。只有付款人或记账人能删：单条带条件的 UPDATE 同时判断"没删过"和"有权限"，不先查再改，
     * 两个请求同时删，只有一个会成功。影响 0 行再回头查原因：
     * 看得见但没权限 → 403；其余（不存在、已删除、别的行程的、别人的个人支出）→ 404。
     */
    void delete(long userId, long tripId, long expenseId) {
        ActiveMember me = memberService.requireActiveMember(userId, tripId);
        if (expenseMapper.softDelete(tripId, expenseId, me.memberId()) == 1) {
            return;
        }

        ExpenseRow row = expenseMapper.findVisible(tripId, expenseId, me.memberId());
        boolean canDelete = row != null && (row.paidByMemberId() == me.memberId() || row.createdByMemberId() == me.memberId());
        if (row != null && !canDelete) {
            throw ApiException.forbidden("Only the payer or the creator can delete this expense");
        }
        throw ApiException.notFound();
    }

    PageResult<ExpenseView> list(long userId, long tripId, String date, Long queryMemberId, String currency, ExpenseCategory category) {
        ActiveMember me = memberService.requireActiveMember(userId, tripId);
        LocalDate day = parseDate(date);
        Instant occurredFrom = date == null ? null : day.atStartOfDay(ZoneId.of("UTC")).minusHours(14).toInstant();
        Instant occurredTo = date == null ? null : day.plusDays(1).atStartOfDay(ZoneId.of("UTC")).plusHours(14).toInstant();
        ExpenseQuery expenseQuery = new ExpenseQuery(tripId, me.memberId(), queryMemberId, currency, category, occurredFrom, occurredTo);

        // occurredFrom、occurredTo 只是粗筛的 UTC 范围，是闭区间，精确的"当地日期"由 Service 里按 occurredTz 判断
        List<ExpenseRow> expenses = expenseMapper.list(expenseQuery).stream().filter(expense -> {
            if (date == null) {
                return true;
            }
            LocalDate occurredDate = expense.occurredAt().atZone(ZoneId.of(expense.occurredTz())).toLocalDate();
            return occurredDate.equals(day);
        }).toList();
        if (expenses.isEmpty()) {
            return new PageResult<>(List.of());
        }
        // 获取所有分摊
        List<ExpenseSplitRow> splits = expenseMapper.findSplitsByMultipleExpenseIds(expenses.stream().map(ExpenseRow::id).collect(Collectors.toList()));
        // 转换为 Map<expenseId, List<ExpenseSplitRow>>
        Map<Long, List<ExpenseSplitRow>> collect = splits.stream().collect(Collectors.groupingBy(ExpenseSplitRow::expenseId, Collectors.toList()));
        // 获取行程开始日期
        LocalDate startDate = tripService.getTripClock(userId, tripId).startDate();
        // 转换为 ExpenseView
        List<ExpenseView> expenseViews = expenses.stream().map(expense -> {
            List<SplitRow> splitRows = collect.getOrDefault(expense.id(), List.of()).stream().map(split -> new SplitRow(split.memberId(), split.amountMinor())).toList();
            Long dayIndex = calculateDayIndex(expense.occurredAt(), ZoneId.of(expense.occurredTz()), startDate);
            return ExpenseView.of(expense, splitRows, dayIndex);
        }).toList();

        return new PageResult<>(expenseViews);
    }

    ExpenseView update(long userId, long tripId, long expenseId, UpdateExpenseRequest request) {
        ActiveMember me = memberService.requireActiveMember(userId, tripId);
        ExpenseRow existing = expenseMapper.findVisible(tripId, expenseId, me.memberId());
        if (existing == null) {
            throw ApiException.notFound();
        }
        // 校验是提交更新的成员是否可以更新该支出：付款人或记账人可以更新，其他人不行
        if (me.memberId() != existing.createdByMemberId() && me.memberId() != existing.paidByMemberId()) {
            throw ApiException.forbidden("Only the payer or the creator can update this expense");
        }

        // 不能在个人和共同支出之间切换
        if ((existing.splitMode() == SplitMode.NONE) != (request.splitMode() == SplitMode.NONE)) {
            throw validationFailed("splitMode", "Cannot switch between a personal expense and a trip expense");
        }

        // 获取该 expense 的分摊成员列表
        Set<Long> existingMemberIds = expenseMapper.findSplits(existing.id()).stream().map(SplitRow::memberId).collect(Collectors.toSet());
        existingMemberIds.add(existing.paidByMemberId()); // 有可能付款人不在分摊列表里，所以也要加上付款人
        // 校验 currency、amountMinor，以及成员（数据库里已有的成员不会被校验，新增的则会）
        int roundUnit = validate(tripId, request, existingMemberIds);

        Instant occurredAt = request.occurredAt() == null ? existing.occurredAt() : parseOccurredAt(request.occurredAt());
        String occurredTz = request.occurredTz() == null ? existing.occurredTz() : parseTz(request.occurredTz());

        List<NewSplit> newSplits = SplitCalculator.split(me.memberId(), request, roundUnit).stream().map(splitView -> new NewSplit(tripId, expenseId, splitView.memberId(), splitView.amountMinor())).collect(Collectors.toList());

        UpdateExpense updateExpense = UpdateExpense.fromRequest(tripId, expenseId, me.memberId(), request, occurredAt, occurredTz);

        boolean updated = expenseUpdater.update(updateExpense, newSplits);

        ExpenseRow expenseAfterUpdated = expenseMapper.findVisible(tripId, expenseId, me.memberId());

        if(expenseAfterUpdated == null) {
            // 说明更新后该支出被删除了
            throw ApiException.notFound();
        }

        if (updated) {
            return toView(expenseAfterUpdated, tripService.getTripClock(userId, tripId));
        }

        throw ApiException.of(ErrorCode.VERSION_CONFLICT, "Expense with expenseId " + existing.id() + " has been updated by another request. Please refresh and try again.");
    }

    /**
     * 用库里实际生效的值（含默认值）拼出支出对象：分摊从库里取，dayIndex 按这笔自己的时区算
     */
    private ExpenseView toView(ExpenseRow row, TripClock tripClock) {
        List<SplitRow> splits = expenseMapper.findSplits(row.id());
        Long dayIndex = calculateDayIndex(row.occurredAt(), ZoneId.of(row.occurredTz()), tripClock.startDate());
        return ExpenseView.of(row, splits, dayIndex);
    }

    /**
     * 同一个 clientUuid 再次提交：内容一致就放行（调用方返回已有记录），否则 409。
     * 已删除、别的成员的个人支出（调用者看不到）、内容不一致都是同一个错误和同一句话，不让调用者借提示探测。
     */
    private void checkDuplicateExpense(ActiveMember me, ExpenseRow existing, ExpenseRequest request) {
        if (!isSameRequest(me, existing, request)) {
            throw ApiException.of(ErrorCode.IDEMPOTENCY_KEY_REUSED, "The idempotency key has been used for a different expense");
        }
    }

    private boolean isSameRequest(ActiveMember me, ExpenseRow existing, ExpenseRequest request) {
        if (existing.deletedAt() != null) {
            return false;
        }
        if (existing.visibility() == Visibility.PRIVATE && existing.createdByMemberId() != me.memberId()) {
            return false;
        }

        // 必填：title、amountMinor、currency、paidByMemberId、splitMode
        if (!request.title().equals(existing.title()) ||
                request.amountMinor() != existing.amountMinor() ||
                !request.currency().equals(existing.currency()) ||
                !request.paidByMemberId().equals(existing.paidByMemberId()) ||
                request.splitMode() != existing.splitMode()) {
            return false;
        }

        // 可选：只比请求里实际带了的字段。occurredAt 解析成时间再比（带偏移的写法和库里的 UTC 字符串不同），精度按库的毫秒
        if ((request.occurredAt() != null && !parseOccurredAt(request.occurredAt()).truncatedTo(ChronoUnit.MILLIS).equals(existing.occurredAt())) ||
                (request.occurredTz() != null && !request.occurredTz().equals(existing.occurredTz())) ||
                (request.category() != null && request.category() != existing.category()) ||
                (request.note() != null && !request.note().equals(existing.note())) ||
                (request.source() != null && request.source() != existing.source())) {
            return false;
        }

        return hasSameSplits(me, existing, request);
    }

    /**
     * 把请求按创建时同一套规则重新算一遍，再和库里的分摊逐人比金额。
     * 不走 {@link #validate}：成员后来退出也不影响重放。
     * 付款人不在 equal 的名单里时，余数给名单第一位，所以只是换了名单顺序也算不一致。
     */
    private boolean hasSameSplits(ActiveMember me, ExpenseRow existing, ExpenseRequest request) {
        if (request.splitMode() == SplitMode.NONE) {
            // 个人支出只有付款人一行，金额 = 总额，付款人和总额上面已经比过
            return true;
        }
        // 上面的 validate 没走，重复成员要自己先挡掉，否则下面转 Map 会因为重复的 key 抛异常
        requireNoDuplicateMembers(request.splits() == null ? List.of() : request.splits());

        int roundingUnit = requireRoundingUnit(request.currency());
        Map<Long, Long> requested = SplitCalculator.split(me.memberId(), request, roundingUnit).stream()
                .collect(Collectors.toMap(SplitView::memberId, SplitView::amountMinor));
        Map<Long, Long> stored = expenseMapper.findSplits(existing.id()).stream()
                .collect(Collectors.toMap(SplitRow::memberId, SplitRow::amountMinor));
        return requested.equals(stored);
    }

    /**
     * 要查库的校验：币别、取整、成员（都在籍）。返回币别的取整单位，给 {@link SplitCalculator#split} 用。
     * 重放已有支出时不调它：成员可能后来退出了，那不该让重放失败。
     */
    private int validate(long tripId, SplitInput request) {
        return validate(tripId, request, Set.of());
    }

    /**
     * existingMemberIds：修改某笔支出时，这笔里原本就有的人，即使已退出也放行；新加入的仍必须在籍。创建传空集合。
     */
    private int validate(long tripId, SplitInput request, Set<Long> existingMemberIds) {
        int roundingUnit = requireRoundingUnit(request.currency());
        SplitCalculator.requireRoundable(request.amountMinor(), roundingUnit);
        requireValidMembers(tripId, request, existingMemberIds);
        return roundingUnit;
    }

    /**
     * 这笔支出里原本就有的人：原来的付款人加上原来的分摊者。没有 expenseId 时是空集合。
     * 看不见的支出（不存在、已删除、别的行程的、别人的个人支出）→ 404，和详情一样不泄露它存在过。
     */
    private Set<Long> membersAlreadyIn(ActiveMember me, long tripId, Long expenseId) {
        if (expenseId == null) {
            return Set.of();
        }
        ExpenseRow row = expenseMapper.findVisible(tripId, expenseId, me.memberId());
        if (row == null) {
            throw ApiException.notFound();
        }
        Set<Long> memberIds = new HashSet<>();
        memberIds.add(row.paidByMemberId());
        expenseMapper.findSplits(row.id()).forEach(split -> memberIds.add(split.memberId()));
        return memberIds;
    }

    // ---- 校验 ----

    private int requireRoundingUnit(String currencyCode) {
        CurrencyView currency = currencyMapper.find(currencyCode);
        if (currency == null) {
            throw validationFailed("currency", "unsupported currency");
        }
        return currency.roundingUnit();
    }

    /**
     * 付款人和所有分摊成员：名单里不能有重复，且都必须是这个行程的在籍成员。
     * existingMemberIds 里的人（这笔支出原本就有的）不查：他们一定是这个行程的成员，已退出也放行。
     */
    private void requireValidMembers(long tripId, SplitInput request, Set<Long> existingMemberIds) {
        List<SplitRequest> splits = request.splits() == null ? List.of() : request.splits();
        requireNoDuplicateMembers(splits);

        // 付款人排在最前，先检查
        List<Long> memberIds = new ArrayList<>();
        memberIds.add(request.paidByMemberId());
        splits.forEach(split -> memberIds.add(split.memberId()));
        memberIds.removeIf(existingMemberIds::contains);
        memberService.requireActiveMembers(tripId, memberIds);
    }

    /**
     * 只看请求本身，放在查库之前
     */
    private static void requireNoDuplicateMembers(List<SplitRequest> splits) {
        Set<Long> seen = new HashSet<>();
        for (SplitRequest split : splits) {
            if (!seen.add(split.memberId())) {
                throw validationFailed("splits", "duplicate memberId " + split.memberId());
            }
        }
    }
}
