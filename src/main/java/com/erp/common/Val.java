package com.erp.common;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/** 数据库返回值 -> Java 类型的统一转换（兼容 H2 / MySQL 对时间、数字的不同实现） */
public final class Val {

    private Val() {
    }

    public static int intOf(Object v) {
        return (int) longOf(v);
    }

    public static long longOf(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static BigDecimal decOf(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    public static String strOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    public static LocalDate dateOf(Object v) {
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        if (v instanceof LocalDate ld) {
            return ld;
        }
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        if (v instanceof Date d) {
            return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String s = strOf(v).replace('T', ' ');
        return s.length() >= 10 ? LocalDate.parse(s.substring(0, 10)) : LocalDate.now();
    }
}
