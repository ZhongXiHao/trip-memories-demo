package com.cshlands.api.member;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
interface MemberMapper {

    @Insert("INSERT INTO trip_member (trip_id, user_id, role, status) " +
            "VALUES (#{tripId}, #{userId}, #{role}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewMember newMember);

}
