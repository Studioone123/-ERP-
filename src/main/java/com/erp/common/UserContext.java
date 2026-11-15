package com.erp.common;

/** 保存当前请求的操作人，供操作日志使用 */
public final class UserContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(String username) {
        CURRENT.set(username);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
