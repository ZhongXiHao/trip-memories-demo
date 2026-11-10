package com.cshlands.api.command;


import com.cshlands.api.expense.ExpenseDraft;
import com.cshlands.api.expense.Source;
import com.cshlands.api.expense.SplitMode;
import com.cshlands.api.member.MemberStatus;
import com.cshlands.api.member.MemberView;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则指令的解析器：纯函数，不查库、不依赖 Spring，只看输入和 {@link ParseContext}。
 * 规则见契约「规则指令」；测试见 CommandPayParseTest、CommandParseTest。
 */
final class CommandParser {

    private CommandParser() {
    }

    private static final String NUMBER = "(?<number>(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)";
    private static final String UNIT = "(?<unit>[A-Za-z]{3}|[¥￥$€£₩円元])";

    /**
     * 1200、1200jpy、1200円
     */
    private static final Pattern AMOUNT_UNIT_AFTER = Pattern.compile(NUMBER + UNIT + "?");
    /**
     * jpy1200、¥1200
     */
    private static final Pattern AMOUNT_UNIT_BEFORE = Pattern.compile(UNIT + NUMBER);

    private static final Map<String, String> COMMAND_ALIASES = Map.of(
            "记账", "pay",
            "記帳", "pay",
            "欠款", "owe",
            "今天", "today"
    );

    private static final Set<String> UNSUPPORTED_COMMANDS = Set.of("todo", "visited", "missed", "skip", "mood", "note");

    private static final Map<String, CommandHandler> HANDLERS = Map.of(
            "pay", CommandParser::parsePay,
            "owe", CommandParser::parseOwe,
            "today", CommandParser::parseToday);

    private static ParseResult parseToday(String string, ParseContext parseContext) {
        return new ParseResult(ParseStatus.OK, "today", CommandKind.VIEW, null, ViewTarget.TODAY_EXPENSES, List.of(), List.of());
    }

    private static ParseResult parseOwe(String string, ParseContext parseContext) {
        return new ParseResult(ParseStatus.OK, "owe", CommandKind.VIEW, null, ViewTarget.DEBTS, List.of(), List.of());
    }

    // ---- /pay ----

    /**
     * 第 1 步的结果：参数里的词分成四堆，都保持输入里的顺序和原文（点名、付款人带着 @）
     */
    private record PayWords(
            List<String> amountCandidates,
            List<String> payerMentions,
            List<String> mentions,
            List<String> titleWords
    ) {
    }

    /**
     * 取到的金额：amountMinor 已经 ×100，currency 是大写的币别码
     */
    private record Money(long amountMinor, String currency) {
    }

    /**
     * 只负责把三步串起来，最后组装一次结果。规则都在下面的小方法里：
     * 每个小方法返回"取到的值或 null"，有问题就往 issues / warnings 里加，不自己 return 整个结果。
     */
    private static ParseResult parsePay(String args, ParseContext context) {
        List<ParseIssue> issues = new ArrayList<>();
        List<ParseWarning> warnings = new ArrayList<>();

        // 1. 分类
        PayWords words = classifyPayWords(args);

        // 2. 逐堆处理
        Money money = extractAmount(words.amountCandidates(), context, issues, warnings);
        Long payerId = extractPayer(words.payerMentions(), context, issues);
        List<Long> splitMemberIds = extractSplitMembers(words, payerId, context, issues, warnings);
        String title = extractTitle(words.titleWords(), issues);

        // 3. 组装：failed 时 draft 也要带上已识别的部分
        ExpenseDraft draft = new ExpenseDraft(
                title,
                money == null ? null : money.amountMinor(),
                money == null ? null : money.currency(),
                payerId,
                SplitMode.EQUAL,
                splitMemberIds.stream().map(ExpenseDraft.Split::new).toList(),
                null,
                null,
                Source.COMMAND);
        ParseStatus status = issues.isEmpty() ? ParseStatus.OK : ParseStatus.FAILED;
        return new ParseResult(status, "pay", CommandKind.EXPENSE, draft, null, issues, warnings);
    }

    /**
     * 第 1 步：按空白切成词，逐个词看它属于哪一类。这里只分类，不判断对错、不加 issue。
     * 金额候选：整个词是金额的形状（数字、数字加三位字母码、数字加货币符号）。
     * 付款人：by / 由 后面紧跟的那个 @ 词（连写的 由@Ken 也算）；取走后它不再算点名。
     * 点名：其余以 @ 开头的词，@Ken@Amy 拆成 @Ken、@Amy。
     * 标题：剩下的词，包括后面不是 @ 的 by / 由。
     */
    private static PayWords classifyPayWords(String args) {
        List<String> amountCandidates = new ArrayList<>();
        List<String> payerMentions = new ArrayList<>();
        List<String> mentions = new ArrayList<>();
        List<String> titleWords = new ArrayList<>();

        String[] words = args.split("\\s+");

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if ("".equals(word)) {
                continue; // 忽略空白词
            }
            if (word.startsWith("@")) {
                // 拆开 @Ken@Amy@ 为 @Ken, @Amy, @
                // 从一个 @ 开始，一直取到下一个 @ 之前（或词的末尾），这一段连同开头的 @ 就是一项。
                mentions.addAll(splitMentions(word));
            } else if (word.equalsIgnoreCase("by") || word.equalsIgnoreCase("由")) {
                if (i + 1 < words.length && words[i + 1].startsWith("@")) {
                    // 如果是连输 @Ken@Amy@，要把 @Ken 当做付款人，@Amy 和 @ 作为点名
                    String nextWord = words[i + 1];
                    List<String> afterSplitMentions = splitMentions(nextWord);
                    payerMentions.add(afterSplitMentions.get(0)); // 第一个 @Ken 是付款人
                    mentions.addAll(afterSplitMentions.subList(1, afterSplitMentions.size())); // 后面的 @Amy 和 @ 作为点名
                    i++; // 跳过下一个词，因为它已经被处理了

                } else {
                    titleWords.add(word); // 不是有效的付款人标记，作为标题词
                }
            } else if (word.startsWith("由@")) {
                // 连写的情况，例如 "由@Ken"
                String mentionPart = word.substring(1); // 去掉开头的 "由"
                List<String> afterSplitMentions = splitMentions(mentionPart);
                payerMentions.add(afterSplitMentions.get(0)); // 第一个 @Ken 是付款
                mentions.addAll(afterSplitMentions.subList(1, afterSplitMentions.size())); // 后面的 @Amy 和 @ 作为点名
            } else if (isAmountCandidate(word)) {
                amountCandidates.add(word);
            } else {
                titleWords.add(word);
            }
        }

        return new PayWords(amountCandidates, payerMentions, mentions, titleWords);
    }

    private static List<String> splitMentions(String word) {
        List<String> mentions = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int nextAt = word.indexOf('@', start + 1);
            if (nextAt == -1) {
                mentions.add(word.substring(start));
                break;
            } else {
                mentions.add(word.substring(start, nextAt));
                start = nextAt;
            }
        }
        return mentions;
    }

    /**
     * 金额候选：整个词是金额的形状（数字、数字加三位字母码、数字加货币符号）
     *
     * @param word the word to check
     * @return true if the word is an amount candidate, false otherwise
     */
    private static boolean isAmountCandidate(String word) {
        Matcher afterMatcher = AMOUNT_UNIT_AFTER.matcher(word);
        Matcher beforeMatcher = AMOUNT_UNIT_BEFORE.matcher(word);
        return afterMatcher.matches() || beforeMatcher.matches();
    }

    /**
     * 金额候选 → 金额和币别。0 个：MISSING_AMOUNT；2 个以上：每个各一条 AMBIGUOUS_AMOUNT；
     * 1 个：换算和校验（UNKNOWN_CURRENCY、AMOUNT_NOT_ROUNDABLE、INVALID_AMOUNT），只有数字时取默认币别并加 CURRENCY_DEFAULTED。
     *
     * @return 没取到（缺失或有问题）时为 null
     */
    private static Money extractAmount(List<String> candidates, ParseContext context,
                                       List<ParseIssue> issues, List<ParseWarning> warnings) {
        if (candidates.isEmpty()) {
            issues.add(new ParseIssue(IssueCode.MISSING_AMOUNT, null, "amount is missing"));
            return null;
        }
        if (candidates.size() > 1) {
            for (String candidate : candidates) {
                issues.add(new ParseIssue(IssueCode.AMBIGUOUS_AMOUNT, candidate, "ambiguous amount"));
            }
            return null;
        }
        String candidate = candidates.get(0);
        Matcher afterMatcher = AMOUNT_UNIT_AFTER.matcher(candidate);
        Matcher beforeMatcher = AMOUNT_UNIT_BEFORE.matcher(candidate);
        String numberStr;
        String unitStr;
        if (afterMatcher.matches()) {
            numberStr = afterMatcher.group("number");
            unitStr = afterMatcher.group("unit");
        } else if (beforeMatcher.matches()) {
            numberStr = beforeMatcher.group("number");
            unitStr = beforeMatcher.group("unit");
        } else {
            issues.add(new ParseIssue(IssueCode.INVALID_AMOUNT, candidate, "invalid amount format"));
            return null;
        }

        boolean useDefaultCurrency = false;
        if (unitStr == null) {
            unitStr = context.defaultCurrency();
            useDefaultCurrency = true;
        } else {
            unitStr = unitStr.toUpperCase(Locale.ROOT);
        }
        Map<String, Integer> currencyUnit = context.roundingUnitByCurrency();
        if (!currencyUnit.containsKey(unitStr)) {
            issues.add(new ParseIssue(IssueCode.UNKNOWN_CURRENCY, candidate, "unknown currency"));
            return null;
        }
        int roundingUnit = currencyUnit.get(unitStr);

        BigDecimal decimal = new BigDecimal(numberStr.replace(",", "")).movePointRight(2);
        if (decimal.stripTrailingZeros().scale() > 0) {
            issues.add(new ParseIssue(IssueCode.AMOUNT_NOT_ROUNDABLE, candidate, "amount has more than 2 decimal places"));
            return null;
        }


        long amountMinor;
        try {
            amountMinor = decimal.longValueExact();
        } catch (ArithmeticException e) {
            issues.add(new ParseIssue(IssueCode.INVALID_AMOUNT, candidate, "invalid amount number"));
            return null;
        }

        if (amountMinor == 0) {
            issues.add(new ParseIssue(IssueCode.INVALID_AMOUNT, candidate, "amount cannot be zero"));
            return null;
        }

        if (amountMinor % roundingUnit != 0) {
            issues.add(new ParseIssue(IssueCode.AMOUNT_NOT_ROUNDABLE, candidate, "amount not roundable to " + roundingUnit));
            return null;
        }

        if (useDefaultCurrency) {
            warnings.add(new ParseWarning(WarningCode.CURRENCY_DEFAULTED, candidate, "currency defaulted to " + unitStr));
        }
        return new Money(amountMinor, unitStr);
    }

    /**
     * 一个 @ 词 → memberId，付款人和分摊者共用。忽略大小写：
     * 在籍成员里恰好一个 → 命中；两个以上 → AMBIGUOUS_MEMBER；在籍里没有、已退出里有 → MEMBER_LEFT；都没有 → UNKNOWN_MEMBER。
     *
     * @param mention 带 @ 的原词，issue 的 text 用它
     * @return 没命中时为 null（issue 已经加好）
     */
    private static Long matchMember(String mention, ParseContext context, List<ParseIssue> issues) {
        List<MemberView> tripMembers = context.members();
        String mentionLower = mention.substring(1).toLowerCase(Locale.ROOT); // 去掉开头的 @ 并转小写
        List<MemberView> filteredMembers = tripMembers.stream().filter(m -> m.nickname().toLowerCase(Locale.ROOT).equals(mentionLower)).toList();

        if (filteredMembers.isEmpty()) {
            issues.add(new ParseIssue(IssueCode.UNKNOWN_MEMBER, mention, "unknown member"));
            return null;
        }

        List<MemberView> activeMembers = filteredMembers.stream().filter(m -> MemberStatus.ACTIVE.equals(m.status())).toList();

        if (activeMembers.size() == 1) {
            return activeMembers.get(0).memberId();
        } else if (activeMembers.size() > 1) {
            issues.add(new ParseIssue(IssueCode.AMBIGUOUS_MEMBER, mention, "ambiguous member"));
            return null;
        }
        // 没有在籍成员，说明都已退出
        issues.add(new ParseIssue(IssueCode.MEMBER_LEFT, mention, "member has left"));
        return null;
    }

    /**
     * 付款人。0 个：自己（context.myMemberId()）；1 个：matchMember；2 个以上：AMBIGUOUS_PAYER。
     *
     * @return 付款人有问题时为 null
     */
    private static Long extractPayer(List<String> payerMentions, ParseContext context, List<ParseIssue> issues) {
        if (payerMentions.isEmpty()) {
            return context.myMemberId();
        }
        if (payerMentions.size() > 1) {
            issues.add(new ParseIssue(IssueCode.AMBIGUOUS_PAYER, String.join(" ", payerMentions), "ambiguous payer"));
            return null;
        }
        String mention = payerMentions.get(0);
        return matchMember(mention, context, issues);
    }

    /**
     * 分摊者的 memberId，按 memberId 升序、不重复。
     * 有点名：命中的成员加上付款人（认不出的不进名单）；用 by / 由 指定的付款人不在点名里 → PAYER_ADDED_TO_SPLIT。
     * 没有任何点名：全体在籍成员，加 SPLIT_ALL_MEMBERS。
     *
     * @param payerId 可能为 null（付款人有问题），此时名单里就没有付款人
     */
    private static List<Long> extractSplitMembers(PayWords words, Long payerId, ParseContext context,
                                                  List<ParseIssue> issues, List<ParseWarning> warnings) {
        List<String> mentions = words.mentions();
        if (mentions.isEmpty()) {
            // 没有任何点名：全体在籍成员
            List<Long> splitMemberIds = context.members().stream()
                    .filter(m -> MemberStatus.ACTIVE.equals(m.status()))
                    .map(MemberView::memberId)
                    .sorted().toList();
            warnings.add(new ParseWarning(WarningCode.SPLIT_ALL_MEMBERS, null, "split among all members"));
            return splitMemberIds;
        }

        Set<Long> splitMemberIds = new HashSet<>();
        // 去重
        Set<String> uniqueMentions = new LinkedHashSet<>(mentions);
        for (String mention : uniqueMentions) {
            Long memberId = matchMember(mention, context, issues);
            if (memberId != null) {
                splitMemberIds.add(memberId);
            }
        }

        if (payerId != null && !splitMemberIds.contains(payerId) && !words.payerMentions().isEmpty()) {
            warnings.add(new ParseWarning(WarningCode.PAYER_ADDED_TO_SPLIT, words.payerMentions().get(0), "payer added to split members"));
        }

        if (payerId != null) {
            splitMemberIds.add(payerId);
        }

        return splitMemberIds.stream().sorted().toList();
    }

    /**
     * 标题：词用一个空格连起来。为空 → MISSING_TITLE；超过 128 字符 → TITLE_TOO_LONG。
     *
     * @return 有问题时为 null
     */
    private static String extractTitle(List<String> titleWords, List<ParseIssue> issues) {
        if (titleWords.isEmpty()) {
            issues.add(new ParseIssue(IssueCode.MISSING_TITLE, null, "title is missing"));
            return null;
        }
        String title = String.join(" ", titleWords);
        if (title.length() > 128) {
            issues.add(new ParseIssue(IssueCode.TITLE_TOO_LONG, title, "title is too long"));
            return null;
        }
        return title;
    }

    /**
     * 严格模式：契约里的指令，必须以 / 开头
     */
    static ParseResult parseStrict(String input, ParseContext context) {
        String normalized = normalize(input);
        List<ParseIssue> issues = new ArrayList<>();
        if (!normalized.startsWith("/")) {
            issues.add(new ParseIssue(IssueCode.NOT_A_COMMAND, normalized, "command must start with '/'"));
            return new ParseResult(ParseStatus.FAILED, null, null, null, null, issues, List.of());
        }
        // 用正则匹配 / 到第一个空格之间的内容，作为指令名
        String[] parts = normalized.split("\\s+", 2);
        String command = parts[0].substring(1).toLowerCase(Locale.ROOT); // 去掉 / 并转小写

        // 处理别名
        command = COMMAND_ALIASES.getOrDefault(command, command);
        if (UNSUPPORTED_COMMANDS.contains(command)) {
            issues.add(new ParseIssue(IssueCode.COMMAND_NOT_AVAILABLE, parts[0], "unsupported command"));
            return new ParseResult(ParseStatus.UNSUPPORTED, command, null, null, null, issues, List.of());
        }

        // 根据指令名选择对应的处理器
        CommandHandler handler = HANDLERS.get(command);
        if (handler == null) {
            issues.add(new ParseIssue(IssueCode.UNKNOWN_COMMAND, parts[0], "unknown command"));
            return new ParseResult(ParseStatus.FAILED, null, null, null, null, issues, List.of());
        }

        return handler.handle(parts.length > 1 ? parts[1] : "", context);
    }

    /**
     * 去除前后空白，并将符号 全角空格、／、＠ 替换成半角
     */
    static String normalize(String input) {
        String stripped = input.strip();
        StringBuilder sb = new StringBuilder(stripped.length());
        for (char c : stripped.toCharArray()) {
            switch (c) {
                case '／' -> sb.append('/');
                case '＠' -> sb.append('@');
                case '　' -> sb.append(' ');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
