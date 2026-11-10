package com.cshlands.api.command;

import com.cshlands.api.currency.CurrencyMapper;
import com.cshlands.api.currency.CurrencyView;
import com.cshlands.api.expense.ExpensePreview;
import com.cshlands.api.expense.ExpensePreviewer;
import com.cshlands.api.member.ActiveMember;
import com.cshlands.api.member.MemberService;
import com.cshlands.api.trip.TripService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 取解析要用的数据、调纯函数的解析器、解析成功时算预览。解析规则本身在 {@link CommandParser}，这里不查规则。
 */
@Service
class CommandService {
    private final MemberService memberService;
    private final TripService tripService;
    private final CurrencyMapper currencyMapper;
    private final ExpensePreviewer expensePreviewer;

    CommandService(MemberService memberService, TripService tripService, CurrencyMapper currencyMapper,
                   ExpensePreviewer expensePreviewer) {
        this.memberService = memberService;
        this.tripService = tripService;
        this.currencyMapper = currencyMapper;
        this.expensePreviewer = expensePreviewer;
    }

    CommandParseView parse(long userId, long tripId, String input) {
        ActiveMember me = memberService.requireActiveMember(userId, tripId);
        Map<String, Integer> roundingUnitByCurrency = currencyMapper.list().stream()
                .collect(Collectors.toMap(CurrencyView::code, CurrencyView::roundingUnit));
        ParseContext context = new ParseContext(
                me.memberId(),
                tripService.getDefaultCurrency(userId, tripId),
                memberService.list(userId, tripId, null),
                roundingUnitByCurrency);

        ParseResult result = CommandParser.parseStrict(input, context);

        // 只有完整的支出草稿才算预览；这一步的 ApiException（并发时刚好有人退出等）照常变成 4xx，见契约
        ExpensePreview preview = result.status() == ParseStatus.OK && result.kind() == CommandKind.EXPENSE
                ? expensePreviewer.preview(userId, tripId, result.draft())
                : null;
        return CommandParseView.of(result, preview);
    }
}
