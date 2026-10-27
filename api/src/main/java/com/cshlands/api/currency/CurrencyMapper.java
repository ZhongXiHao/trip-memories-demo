package com.cshlands.api.currency;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CurrencyMapper {
    @Select("SELECT code, name, rounding_unit" +
            " FROM currency ORDER BY code")
    List<CurrencyView> list();

    @Select("SELECT COUNT(*) > 0 FROM currency WHERE code = #{code}")
    boolean exists(String code);

    /** 币别不存在时返回 null */
    @Select("SELECT code, name, rounding_unit" +
            " FROM currency WHERE code = #{code}")
    CurrencyView find(String code);
}
