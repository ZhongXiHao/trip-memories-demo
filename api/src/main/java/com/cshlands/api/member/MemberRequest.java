package com.cshlands.api.member;


import jakarta.validation.constraints.NotNull;

record MemberRequest(@NotNull Long userId) {
}
