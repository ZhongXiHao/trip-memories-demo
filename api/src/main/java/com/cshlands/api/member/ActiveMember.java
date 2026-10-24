package com.cshlands.api.member;

/** 鉴权通过后的结果：调用者在这个 Trip 里的成员 id 和角色 */
public record ActiveMember(long memberId, MemberRole role) {
}
