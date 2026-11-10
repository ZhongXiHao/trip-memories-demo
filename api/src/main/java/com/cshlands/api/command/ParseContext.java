package com.cshlands.api.command;

import com.cshlands.api.member.MemberView;

import java.util.List;
import java.util.Map;

/**
 * 解析器需要的、从库里读出来的东西。解析器是纯函数，只看输入和它。
 *
 * @param myMemberId             调用者的 memberId（不是 userId），默认付款人
 * @param defaultCurrency        行程的默认币别，只写数字时取它
 * @param members                本行程所有成员，含已退出的（靠 status 区分），按 memberId 升序
 * @param roundingUnitByCurrency 币别列表：币别码（大写）→ roundingUnit
 */
record ParseContext(
        long myMemberId,
        String defaultCurrency,
        List<MemberView> members,
        Map<String, Integer> roundingUnitByCurrency
) {
}
