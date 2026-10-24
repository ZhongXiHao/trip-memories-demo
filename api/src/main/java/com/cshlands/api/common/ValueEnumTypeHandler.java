package com.cshlands.api.common;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 按 {@link ValueEnum#value()} 读写枚举。MyBatis 默认的处理器用 name()，
 * 写进去是 OWNER，读回 owner 时 valueOf 会失败。
 * 每个枚举写一个子类，标 @Component 和 @MappedTypes 即可注册。
 */
public abstract class ValueEnumTypeHandler<E extends Enum<E> & ValueEnum> extends BaseTypeHandler<E> {

    private final Class<E> type;

    protected ValueEnumTypeHandler(Class<E> type) {
        this.type = type;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, E parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.value());
    }

    @Override
    public E getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return fromValue(rs.getString(columnName));
    }

    @Override
    public E getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return fromValue(rs.getString(columnIndex));
    }

    @Override
    public E getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return fromValue(cs.getString(columnIndex));
    }

    private E fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.value().equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " value: " + value);
    }

}
