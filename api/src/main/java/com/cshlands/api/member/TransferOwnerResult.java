package com.cshlands.api.member;

/** 转让 Owner 的结果；字段名就是响应里的 JSON 字段名 */
record TransferOwnerResult(MemberView previousOwner, MemberView newOwner) {
}
