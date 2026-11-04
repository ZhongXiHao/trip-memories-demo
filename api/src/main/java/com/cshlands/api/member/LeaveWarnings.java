package com.cshlands.api.member;

import com.cshlands.api.common.Warning;

import java.util.List;

public interface LeaveWarnings {
    List<Warning> of(long tripId, long memberId);
}
