package com.erp.common;

/**
 * 统一响应体：code=0 成功；401 未登录；其它值为业务失败
 */
public record R(int code, String msg, Object data) {

    public static R ok() {
        return new R(0, "ok", null);
    }

    public static R ok(Object data) {
        return new R(0, "ok", data);
    }

    public static R fail(String msg) {
        return new R(1, msg, null);
    }

    public static R fail(int code, String msg) {
        return new R(code, msg, null);
    }
}
