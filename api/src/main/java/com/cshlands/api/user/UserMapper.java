package com.cshlands.api.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("SELECT id, nickname FROM app_user WHERE id = #{id}")
    UserView findById(long id);

    @Insert("INSERT INTO app_user (nickname) VALUES (#{nickname})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewUser user);


    @Select("SELECT COUNT(*) > 0 FROM app_user WHERE id = #{id}")
    boolean exists(long id);
}
