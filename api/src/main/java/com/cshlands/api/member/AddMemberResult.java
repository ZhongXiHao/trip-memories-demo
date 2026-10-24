package com.cshlands.api.member;

/**
 * 添加成员的结果。created 为 true 是新建了成员（201），false 是曾经退出的成员重新加入（200）。
 * 状态码是 HTTP 层的事，由 controller 根据这个标志决定。
 */
record AddMemberResult(MemberView member, boolean created) {
}
