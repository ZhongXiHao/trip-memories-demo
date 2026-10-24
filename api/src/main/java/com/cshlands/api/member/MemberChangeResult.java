package com.cshlands.api.member;

import com.cshlands.api.common.Warning;

import java.util.List;

record MemberChangeResult(MemberView member, List<Warning> warnings) {
}
