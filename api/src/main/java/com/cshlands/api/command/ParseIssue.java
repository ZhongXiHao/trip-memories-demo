package com.cshlands.api.command;

/**
 * 无法识别的部分。
 *
 * @param text 输入里对应的原文片段；没有对应片段时（MISSING_AMOUNT、MISSING_TITLE）为 null
 */
record ParseIssue(IssueCode code, String text, String message) {
}
