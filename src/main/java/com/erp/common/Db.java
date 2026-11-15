package com.erp.common;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * JdbcTemplate 极简封装：ERP 的查询以列表/单条/单值为主，
 * 统一放这里可以让业务层只关注 SQL 和流程，同时保留完整 SQL 可读性。
 */
@Component
public class Db {

    private final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    /**
     * 查询单行。
     *
     * <p><b>优化点</b>：原实现是 {@code queryForList(...).get(0)}，驱动会把符合条件的所有行
     * 都拉回 JVM 再丢掉多余的，遇到没有 LIMIT 的查询（例如按非唯一条件查日志）会白白传输
     * 大量数据。这里改用 JDBC 标准的 {@code PreparedStatement#setMaxRows(1)}，
     * 在驱动层就限制只取一行 —— 既不用改写 SQL（能兼容任意子查询 / 已带 LIMIT 的语句，
     * H2 与 MySQL 都支持），语义又和原来完全一致：无结果返回 null。
     */
    public Map<String, Object> one(String sql, Object... args) {
        return jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.setMaxRows(1);
            return ps;
        }, rs -> rs.next() ? new ColumnMapRowMapper().mapRow(rs, 0) : null);
    }

    public <T> T value(String sql, Class<T> type, Object... args) {
        try {
            return jdbc.queryForObject(sql, type, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public int exec(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    /** 插入并返回自增主键 */
    public long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, holder);
        // H2 会把带默认值的列（如 created_at）一并返回，MySQL 只返回主键，
        // 因此按列名 id 精确提取，而不是依赖 getKey()（多列时会抛异常）。
        if (holder.getKeyList() != null && !holder.getKeyList().isEmpty()) {
            Map<String, Object> keys = holder.getKeyList().get(0);
            Object v = null;
            for (Map.Entry<String, Object> e : keys.entrySet()) {
                if ("id".equalsIgnoreCase(e.getKey())) {
                    v = e.getValue();
                    break;
                }
            }
            if (v == null && keys.size() == 1) {
                v = keys.values().iterator().next();
            }
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v != null) {
                return Long.parseLong(String.valueOf(v));
            }
        }
        return 0L;
    }

    /** 统一把前端传来的值转成字符串（null -> ""） */
    public static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 统一把前端传来的值转成 long，非法值返回 defaultValue */
    public static long lng(Map<String, Object> body, String key, long defaultValue) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new BizException("参数 " + key + " 必须是数字");
        }
    }

    public static int integer(Map<String, Object> body, String key, int defaultValue) {
        return (int) lng(body, key, defaultValue);
    }

    public static java.math.BigDecimal decimal(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return java.math.BigDecimal.ZERO;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new BizException("参数 " + key + " 金额格式不正确");
        }
    }
}
