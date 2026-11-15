package com.erp.common;

import java.util.*;

/**
 * 基于角色的访问控制（RBAC）—— 简化自金蝶的「角色 + 功能权限 + 数据权限」体系。
 *
 * <p>金蝶的权限模型包含「功能权限（能点哪些菜单）、字段权限、数据权限（能看哪些部门数据）」。
 * 本系统实现前两层中最关键的部分：
 * <ul>
 *   <li><b>菜单权限</b>：登录后返回该角色可见的页面 key，前端据此渲染侧边栏</li>
 *   <li><b>接口权限</b>：拦截器按 URL 一级模块 + HTTP 方法校验，防止绕过页面直接调接口</li>
 * </ul>
 *
 * <p>为什么前端隐藏菜单还不够？因为菜单只是 UI，任何人都能用 curl 调 /api/finance/vouchers。
 * 所以后端必须有第二道闸：{@code canAccess(...)} 由 AuthInterceptor 调用。
 *
 * <p>数据权限（例如销售员只能看自己的客户）在真实 ERP 中由组织架构与数据范围规则实现，
 * 这里未实现，代码导读文档中说明了扩展点。
 */
public final class RolePermission {

    private RolePermission() {
        // 纯静态工具类，禁止实例化
    }

    /** 内置角色：编码 → 显示名。真实金蝶允许自定义角色，这里固化以简化演示 */
    public static final Map<String, String> ROLES = new LinkedHashMap<>();

    static {
        ROLES.put("ADMIN", "系统管理员");
        ROLES.put("PURCHASE", "采购专员");
        ROLES.put("SALES", "销售专员");
        ROLES.put("WAREHOUSE", "仓库管理员");
        ROLES.put("FINANCE", "财务会计");
        ROLES.put("PRODUCTION", "生产计划员");
    }

    /** 每个角色允许访问的后端模块（URL 中 /api/ 后的一级路径） */
    private static final Map<String, Set<String>> ROLE_MODULES = new HashMap<>();

    /** 每个角色可见的页面 key；值为 null 表示「全部可见」（管理员） */
    private static final Map<String, Set<String>> ROLE_MENUS = new HashMap<>();

    // 所有角色都需要的公共模块：登录态、看板、基础资料（只读）、运维状态、数据导出（只读）、AI 分析
    // AI 分析的数据全部来自看板级汇总，不含敏感明细，因此与看板同级向全角色开放
    private static final Set<String> COMMON = Set.of("auth", "dashboard", "master", "ops", "export", "ai");

    static {
        ROLE_MODULES.put("ADMIN", Set.of("*"));
        ROLE_MODULES.put("PURCHASE", union(COMMON, "purchase", "inventory"));
        ROLE_MODULES.put("SALES", union(COMMON, "sales", "inventory", "settle"));
        ROLE_MODULES.put("WAREHOUSE", union(COMMON, "inventory", "mf"));
        ROLE_MODULES.put("FINANCE", union(COMMON, "finance", "settle", "purchase", "sales", "inventory"));
        ROLE_MODULES.put("PRODUCTION", union(COMMON, "mf", "inventory"));

        ROLE_MENUS.put("ADMIN", null); // null = 全部
        ROLE_MENUS.put("PURCHASE", Set.of(
                "dashboard", "ai", "purchase", "stock", "flows",
                "product", "supplier", "customer", "warehouse", "logs"));
        ROLE_MENUS.put("SALES", Set.of(
                "dashboard", "ai", "sales", "stock", "flows",
                "product", "customer", "warehouse", "arap", "settlements", "logs"));
        ROLE_MENUS.put("WAREHOUSE", Set.of(
                "dashboard", "ai", "stock", "flows", "product", "warehouse",
                "boms", "mo", "logs"));
        ROLE_MENUS.put("FINANCE", Set.of(
                "dashboard", "ai", "vouchers", "subjects", "ledger", "reports", "periods",
                "arap", "settlements", "aging", "purchase", "sales", "logs"));
        ROLE_MENUS.put("PRODUCTION", Set.of(
                "dashboard", "ai", "boms", "mo", "stock", "flows", "product", "warehouse", "logs"));
    }

    /**
     * 返回该角色可见的页面 key 列表。
     * 管理员返回 {@code ["*"]}，前端看到 * 就渲染全部菜单。
     */
    public static List<String> menus(String role) {
        Set<String> set = ROLE_MENUS.getOrDefault(role, Set.of("dashboard"));
        if (set == null) {
            return List.of("*");
        }
        return new ArrayList<>(set);
    }

    /**
     * 后端接口鉴权。
     *
     * @param role   当前登录角色
     * @param uri    请求路径，如 /api/finance/vouchers
     * @param method HTTP 方法
     */
    public static boolean canAccess(String role, String uri, String method) {
        if (role == null || "ADMIN".equals(role)) {
            return true; // 管理员放行
        }
        String[] parts = uri.split("/");
        if (parts.length < 3) {
            return true;
        }
        String module = parts[2];
        Set<String> allowed = ROLE_MODULES.getOrDefault(role, COMMON);
        if (!allowed.contains("*") && !allowed.contains(module)) {
            return false;
        }
        // 基础资料属于企业级主数据，非管理员只读；
        // 库存盘点 / 调整这类写操作只允许管理员和仓管执行。
        boolean write = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
        if (write && "master".equals(module)) {
            return false;
        }
        if (write && "system".equals(module)) {
            return false;
        }
        return true;
    }

    /** 角色编码合法性校验，防止写入不存在的角色导致登录后无权限 */
    public static void assertRole(String role) {
        if (!ROLES.containsKey(role)) {
            throw new BizException("未知角色：" + role);
        }
    }

    private static Set<String> union(Set<String> base, String... extra) {
        Set<String> set = new HashSet<>(base);
        Collections.addAll(set, extra);
        return Set.copyOf(set);
    }
}
