package com.erp.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 分页工具：同时支持「数据库分页」和「内存分页」，并把分页做成可选能力。
 *
 * <h3>兼容性设计</h3>
 * 现有前端所有列表请求都不带 page 参数。为了不改动前端，Controller 统一用
 * {@link #reply}：page 为空时返回原来的裸 {@code List}，带了才返回 {@link PageResult}。
 * 这样旧调用零改动、新调用可用分页，接口是渐进增强而不是破坏性升级。
 *
 * <h3>两种分页怎么选（面试可讲）</h3>
 * <ul>
 *   <li><b>SQL 分页</b> {@link #query}：用 {@code COUNT(*)} 子查询 + {@code LIMIT ? OFFSET ?}，
 *       只把当前页的数据拉回 JVM。适合日志、流水这类会一直增长的表。</li>
 *   <li><b>内存分页</b> {@link #slice}：适合订单列表这种「先 join 明细、再按单聚合」的查询，
 *       SQL 层直接分页会把同一张单的明细切到两页。数据量可控时先在 Service 组装好再切页更正确。</li>
 * </ul>
 * 深分页（offset 很大）两种方案都会变慢，生产上应改用「游标分页 / 基于主键的条件翻页」，
 * 这里的数据量没有到那个量级，不做过度设计。
 */
public final class PageSupport {

    /** 默认每页条数与上限：防止前端误传 size=1000000 把内存打爆 */
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    private PageSupport() {
    }

    /** 把外部入参归一化：页码从 1 开始，页大小限制在 1~200 */
    public static int[] normalize(Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new int[]{p, s};
    }

    /** 内存切页，入参越界时返回空列表而不是抛异常 */
    public static <T> PageResult<T> slice(List<T> all, Integer page, Integer size) {
        int[] ps = normalize(page, size);
        int p = ps[0];
        int s = ps[1];
        List<T> safe = all == null ? List.of() : all;
        long total = safe.size();
        int from = (int) Math.min((long) (p - 1) * s, total);
        int to = Math.min(from + s, safe.size());
        // 用 new ArrayList 包一层：subList 返回的是视图，Jackson 序列化到 Redis 缓存时
        // 无法反序列化（这个类之前踩过 ArrayList$SubList 的坑），统一转成普通 List 更稳。
        return new PageResult<>(new ArrayList<>(safe.subList(from, to)), total, p, s);
    }

    /**
     * SQL 级分页：先查总数，再查当前页。
     *
     * @param sql  完整的查询 SQL（可带 ORDER BY，但不能已带 LIMIT）
     * @param args SQL 中占位符对应的参数
     */
    public static PageResult<Map<String, Object>> query(Db db, String sql, Object[] args, Integer page, Integer size) {
        int[] ps = normalize(page, size);
        int p = ps[0];
        int s = ps[1];
        Object[] safeArgs = args == null ? new Object[0] : args;

        // count 时去掉 ORDER BY：排序对计数没有意义，某些数据库还会因子查询内排序报错
        String countSql = "SELECT COUNT(*) FROM (" + stripOrderBy(sql) + ") erp_page_count";
        Integer count = db.value(countSql, Integer.class, safeArgs);
        long total = count == null ? 0 : count;

        Object[] pageArgs = Arrays.copyOf(safeArgs, safeArgs.length + 2);
        pageArgs[safeArgs.length] = s;
        pageArgs[safeArgs.length + 1] = (long) (p - 1) * s;
        List<Map<String, Object>> rows = db.list(sql + " LIMIT ? OFFSET ?", pageArgs);
        return new PageResult<>(rows, total, p, s);
    }

    /** Controller 一行搞定「可选分页」：没传 page 就保持原来的裸 List 响应 */
    public static R reply(List<?> rows, Integer page, Integer size) {
        return page == null ? R.ok(rows) : R.ok(slice(rows, page, size));
    }

    /** 去掉 SQL 末尾的 ORDER BY 子句（大小写不敏感，仅处理末尾，避免误伤子查询） */
    private static String stripOrderBy(String sql) {
        return sql.replaceAll("(?is)\\s+ORDER\\s+BY\\s+[^)]*$", "");
    }
}
