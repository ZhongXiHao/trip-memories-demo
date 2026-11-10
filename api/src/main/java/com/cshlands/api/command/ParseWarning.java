package com.cshlands.api.command;

/**
 * 用户该留意的事，结构同 {@link ParseIssue}
 */
record ParseWarning(WarningCode code, String text, String message) {
}
