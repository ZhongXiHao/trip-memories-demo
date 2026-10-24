package com.cshlands.api.currency;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
interface CurrencyMapper {
    @Select("SELECT code, name, rounding_unit" +
            " FROM currency ORDER BY code")
    List<CurrencyView> list();
}
