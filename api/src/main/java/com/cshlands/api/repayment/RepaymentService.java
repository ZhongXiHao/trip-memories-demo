package com.cshlands.api.repayment;

import com.cshlands.api.common.ApiException;
import com.cshlands.api.common.ErrorCode;
import com.cshlands.api.common.RegexUtils;
import com.cshlands.api.currency.CurrencyMapper;
import com.cshlands.api.currency.CurrencyView;
import com.cshlands.api.expense.ExpenseLookup;
import com.cshlands.api.expense.ExpenseShares;
import com.cshlands.api.member.ActiveMember;
import com.cshlands.api.member.MemberService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.cshlands.api.common.ApiException.validationFailed;

@Service
class RepaymentService {
    private final MemberService memberService;
    private final RepaymentMapper repaymentMapper;
    private final RepaymentWriter repaymentWriter;
    private final ExpenseLookup expenseLookup;
    private final CurrencyMapper currencyMapper;

    RepaymentService(MemberService memberService, RepaymentMapper repaymentMapper, RepaymentWriter repaymentWriter, ExpenseLookup expenseLookup, CurrencyMapper currencyMapper) {
        this.memberService = memberService;
        this.repaymentMapper = repaymentMapper;
        this.repaymentWriter = repaymentWriter;
        this.expenseLookup = expenseLookup;
        this.currencyMapper = currencyMapper;
    }

    /**
     * 首次创建 201；同一个 clientUuid（= settlementGroupId）内容一致的重放 200，返回已有的那一组。
     * <p>
     * 错误的先后（契约）：不是在籍成员 404 → 已有同一 clientUuid 的记录，按重放处理 → 创建者不是 from/to 403
     * → expenseId 看不见 404 → 请求本身有错 400 → 额度不足 400 REPAYMENT_EXCEEDS_DEBT（在 RepaymentWriter 里）。
     * <p>
     * 这个方法自己不开事务：事务里第一次读之后快照就固定了，撞唯一键后在同一个事务里重新读，看不到对方刚提交的行。
     * 写库放在另一个 bean 的事务方法里（同 ExpenseService.create）。
     */
    RepaymentResult create(long userId, long tripId, CreateRepaymentRequest request) {
        ActiveMember me = memberService.requireActiveMember(userId, tripId);
        String clientUuid = request.clientUuid();
        if (!RegexUtils.isValidUuid(clientUuid)) {
            throw validationFailed("clientUuid", "must be a valid UUID");
        }

        // 第二轮只在窄竞态时出现：别的请求刚好抢先写入，write 返回 false，回到开头重新读，走"已有记录"那条路
        for (int attempt = 0; attempt < 2; attempt++) {
            List<RepaymentRow> existing = repaymentMapper.findByGroup(tripId, clientUuid);
            if (!existing.isEmpty()) {
                if (!isSameRequest(me, existing, request)) {
                    throw ApiException.of(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                            "The idempotency key has been used for a different repayment");
                }
                return new RepaymentResult(toGroupView(clientUuid, existing), false);
            }

            validate(tripId, me, request);

            List<NewRepayment> rows = toNewRepayments(tripId, me, request);
            if (repaymentWriter.write(tripId, request.fromMemberId(), request.toMemberId(), rows)) {
                return new RepaymentResult(toGroupView(clientUuid, repaymentMapper.findByGroup(tripId, clientUuid)), true);
            }
        }
        throw new IllegalStateException("Repayment group " + clientUuid + " could not be created or found");
    }

    /**
     * 校验请求，不对就抛异常，什么都不写库。规则见契约「创建还款」，先后顺序也按契约：
     * <ul>
     *   <li>创建者（me）必须是 fromMemberId 或 toMemberId 之一，否则 403 FORBIDDEN；</li>
     *   <li>expenseId 看不见（不存在、已删除、别的行程的、别人的个人支出）→ 404；排在其他 400 之前；</li>
     *   <li>fromMemberId 和 toMemberId 相同 → 400，fields 指 toMemberId；不属于本行程 → 400 MEMBER_NOT_IN_TRIP
     *       （创建者必须在籍，对方可以已退出，所以这里不用 requireActiveMembers）；</li>
     *   <li>paidCurrency 和每项的 clearedCurrency 必须在币别表里，fields 分别指 paidCurrency、items；</li>
     *   <li>items：不能为空，clearedCurrency 不能重复，三个金额字段都必填且 &gt; 0（fields 指 items）；
     *       clearedCurrency 等于 paidCurrency 时 paidAmountMinor 必须等于 clearedAmountMinor；</li>
     *   <li>clearedAmountMinor 是 clearedCurrency 的取整单位的整数倍，paidAmountMinor 是 paidCurrency 的整数倍，
     *       否则 400 AMOUNT_NOT_ROUNDABLE；</li>
     *   <li>带 expenseId 时：是共同支出、是 toMemberId 付的款、fromMemberId 在它的分摊里、clearedCurrency 等于这笔的币别，
     *       items 只能有一项；不满足 → 400，fields 指 expenseId（超过一项指 items）。</li>
     * </ul>
     * fromMemberId、toMemberId 等必填字段由请求类上的注解保证非空；items 里的元素和其中的金额字段在这里判空，
     * 因为 toNewRepayments 会拆箱。
     */
    private void validate(long tripId, ActiveMember me, CreateRepaymentRequest request) {
        Long fromMemberId = request.fromMemberId();
        Long toMemberId = request.toMemberId();
        long creatorMemberId = me.memberId();
        if (creatorMemberId != fromMemberId && creatorMemberId != toMemberId) {
            throw ApiException.forbidden("Creating member must be either fromMemberId or toMemberId");
        }

        ExpenseShares expense = null;
        if (request.expenseId() != null) {
            expense = expenseLookup.findVisible(tripId, request.expenseId(), creatorMemberId);
            if (expense == null) {
                throw ApiException.notFound();
            }
        }


        if (fromMemberId.equals(toMemberId)) {
            throw validationFailed("toMemberId", "must be different from fromMemberId");
        }

        // 创建者必然是 me，已经被校验过了；对方可能已退出，所以不用 requireActiveMembers
        long otherMemberId = (creatorMemberId == fromMemberId) ? toMemberId : fromMemberId;
        if (!memberService.requireMemberOfTrip(tripId, otherMemberId)) {
            throw ApiException.of(ErrorCode.MEMBER_NOT_IN_TRIP, "The other member is not in the trip");
        }


        List<RepaymentItemRequest> items = request.items();
        if (items == null || items.isEmpty()) {
            throw validationFailed("items", "items cannot be empty");
        }
        validateCurrenciesAndRoundingUnit(request.paidCurrency(), items);

        if (expense != null) {
            if (items.size() > 1) {
                throw validationFailed("items", "When expenseId is provided, items can only have one entry");
            }
            validateExpenses(expense, request);
        }

    }

    private void validateExpenses(ExpenseShares expense, CreateRepaymentRequest request) {
        if (expense.paidByMemberId() != request.toMemberId()) {
            throw validationFailed("expenseId", "The expense is not paid by the toMemberId");
        }
        if (!expense.shareByMemberId().containsKey(request.fromMemberId())) {
            throw validationFailed("expenseId", "The expense is not shared by the fromMemberId");
        }
        String clearedCurrency = request.items().get(0).clearedCurrency();
        if (!expense.currency().equals(clearedCurrency)) {
            throw validationFailed("expenseId", "The expense is not paid by the currency");
        }
    }

    private void validateCurrenciesAndRoundingUnit(String paidCurrency, List<RepaymentItemRequest> items) {
        if (items.contains(null)) {
            throw validationFailed("items", "items cannot contain null entries");
        }

        Map<String, Integer> existingCurrencies = currencyMapper.list().stream().collect(Collectors.toMap(CurrencyView::code, CurrencyView::roundingUnit));

        // paidCurrency 和每项的 clearedCurrency 必须在币别表里，fields 分别指 paidCurrency、items；
        if (!existingCurrencies.containsKey(paidCurrency)) {
            throw validationFailed("paidCurrency", "must be one of currencies");
        }
        Set<String> seenCurrencies = new HashSet<>();
        for (RepaymentItemRequest item : items) {
            String clearedCurrency = item.clearedCurrency();
            // 如果 clearedCurrency 为 null 则必然不在币别表里，直接抛异常
            if (!existingCurrencies.containsKey(clearedCurrency)) {
                itemsValidationFailed("clearedCurrency must be one of valid currencies");
            }
            // clearedCurrency 不能重复
            if (!seenCurrencies.add(clearedCurrency)) {
                itemsValidationFailed("clearedCurrency must be unique across items");
            }
            // 金额都必填且 > 0
            if (item.clearedAmountMinor() == null || item.paidAmountMinor() == null) {
                itemsValidationFailed("clearedAmountMinor and paidAmountMinor must not be null");
            }
            if (item.clearedAmountMinor() <= 0 || item.paidAmountMinor() <= 0) {
                itemsValidationFailed("amounts must be greater than 0");
            }
            // clearedCurrency 等于 paidCurrency 时 paidAmountMinor 必须等于 clearedAmountMinor
            if (clearedCurrency.equals(paidCurrency) && !item.clearedAmountMinor().equals(item.paidAmountMinor())) {
                itemsValidationFailed("clearedAmountMinor must equal paidAmountMinor when clearedCurrency equals paidCurrency");
            }

            requireRoundable(item.clearedAmountMinor(), existingCurrencies.get(clearedCurrency));
            requireRoundable(item.paidAmountMinor(), existingCurrencies.get(paidCurrency));
        }
    }

    // TODO: 这里和 expense.SplitCalculator.requireRoundable 如出一辙，但这个严格来说是 Currency 的方法，之后可能会重构
    private static void requireRoundable(long amountMinor, int roundingUnit) {
        if (amountMinor % roundingUnit != 0) {
            // 当单位是 1 时，只允许保留小数后两位
            String message = roundingUnit == 100 ? "Amount must be an integer" : "Amount must be 2 decimal places";
            throw ApiException.of(ErrorCode.AMOUNT_NOT_ROUNDABLE, message);
        }
    }


    private static void itemsValidationFailed(String message) {
        throw validationFailed("items", message);
    }

    /**
     * 请求转成要插库的行，按 clearedCurrency 升序（所有请求加锁、插入的顺序一致，不会交叉死锁）。
     * 创建者是收款方 → 直接 confirmed；创建者是付款方 → pending。
     */
    private List<NewRepayment> toNewRepayments(long tripId, ActiveMember me, CreateRepaymentRequest request) {
        RepaymentStatus status = me.memberId() == request.toMemberId() ? RepaymentStatus.CONFIRMED : RepaymentStatus.PENDING;
        return request.items().stream()
                .sorted(Comparator.comparing(RepaymentItemRequest::clearedCurrency))
                .map(item -> new NewRepayment(
                        tripId,
                        request.fromMemberId(),
                        request.toMemberId(),
                        item.clearedAmountMinor(),
                        item.clearedCurrency(),
                        item.paidAmountMinor(),
                        request.paidCurrency(),
                        request.clientUuid(),
                        request.expenseId(),
                        status,
                        request.note(),
                        me.memberId()))
                .toList();
    }

    /**
     * 同一个 clientUuid 再次提交：内容一致才放行（调用方返回已有的那一组），否则 409。
     * 创建者不同（创建者决定是 pending 还是 confirmed，换人就是不同的意图）、组里有已取消的行、内容不一致，
     * 都是同一个错误和同一句话，调用者没法借提示区分。
     * "内容一致"只比较请求里实际带了的字段：expenseId、note 没带就不参与比较。重放不再检查成员和额度。
     */
    private boolean isSameRequest(ActiveMember me, List<RepaymentRow> existing, CreateRepaymentRequest request) {
        if (existing.stream().anyMatch(row -> row.deletedAt() != null)) {
            return false;
        }
        RepaymentRow first = existing.get(0);
        if (first.createdByMemberId() != me.memberId()
                || !Objects.equals(request.fromMemberId(), first.fromMemberId())
                || !Objects.equals(request.toMemberId(), first.toMemberId())
                || !Objects.equals(request.paidCurrency(), first.paidCurrency())) {
            return false;
        }
        if (request.expenseId() != null && !request.expenseId().equals(first.expenseId())) {
            return false;
        }
        if (request.note() != null && !request.note().equals(first.note())) {
            return false;
        }

        // 项按币别配对，比销帐和实付两个金额，和顺序无关
        Map<String, RepaymentRow> rowsByCurrency = new HashMap<>();
        existing.forEach(row -> rowsByCurrency.put(row.clearedCurrency(), row));
        if (request.items().size() != existing.size()) {
            return false;
        }
        for (RepaymentItemRequest item : request.items()) {
            RepaymentRow row = rowsByCurrency.get(item.clearedCurrency());
            if (row == null
                    || !Objects.equals(item.clearedAmountMinor(), row.clearedAmountMinor())
                    || !Objects.equals(item.paidAmountMinor(), row.paidAmountMinor())) {
                return false;
            }
        }
        return true;
    }

    private static RepaymentGroupView toGroupView(String settlementGroupId, List<RepaymentRow> rows) {
        return new RepaymentGroupView(settlementGroupId, rows.stream().map(RepaymentView::of).toList());
    }
}
