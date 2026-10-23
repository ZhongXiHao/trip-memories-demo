package com.cshlands.api.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("SELECT id, nickname FROM app_user WHERE id = #{id}")
    UserView findById(long id);

}
