package com.erp.dashboard;

import com.erp.common.CacheHub;
import com.erp.common.Db;
import com.erp.common.Val;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 经营看板：核心指标、近 7 日进销趋势、热销商品、待办单据。
 *
 * <p><b>优化点 1 —— 减少数据库往返</b>：原来的 {@code metrics()} 逐个指标发 11 条
 * COUNT/SUM，一次看板请求要 11 次 JDBC 往返。现在把 11 个标量子查询合成 1 条 SQL，
 * 数据库一次扫描、一次返回。SQL 变长但总耗时明显下降，尤其在 H2 这种文件库上。
 *
 * <p><b>优化点 2 —— 结果缓存</b>：看板是典型的「读多写少」，用
 * {@code @Cacheable} 缓存整份 summary。失效由 {@code CacheEvictInterceptor} 统一负责：
 * 任何成功的 POST/PUT/DELETE 请求返回后清空缓存，所以业务一变，看板立刻跟着变，
 * 不需要在每个 Service 里手写清缓存代码（那样最容易漏）。
 */
@Service
public class DashboardService {

    private final Db db;

    public DashboardService(Db db) {
        this.db = db;
    }

    /**
     * 一次 SQL 取回全部 11 个指标。
     * 只有 4 个 {@code ?} 占位符（本月起始时间，复用 4 次），其余子查询无参数。
     */
    private static final String METRICS_SQL = """
            SELECT
              (SELECT COALESCE(SUM(total_amount),0) FROM sales_order
                 WHERE status='SHIPPED'  AND created_at>=?)                     AS month_sales,
              (SELECT COALESCE(SUM(total_amount),0) FROM purchase_order
                 WHERE status='RECEIVED' AND created_at>=?)                     AS month_purchase,
              (SELECT COALESCE(SUM(i.quantity * p.cost_price),0)
                 FROM inventory i JOIN product p ON p.id=i.product_id)          AS stock_value,
              (SELECT COUNT(*) FROM sales_order    WHERE created_at>=?)         AS month_sales_orders,
              (SELECT COUNT(*) FROM purchase_order WHERE created_at>=?)         AS month_purchase_orders,
              (SELECT COUNT(*) FROM product)                                    AS products,
              (SELECT COUNT(*) FROM customer)                                   AS customers,
              (SELECT COUNT(*) FROM supplier)                                   AS suppliers,
              (SELECT COUNT(*) FROM inventory i JOIN product p ON p.id=i.product_id
                 WHERE i.quantity <= p.safety_stock)                            AS low_stock,
              (SELECT COUNT(*) FROM purchase_order WHERE status='CONFIRMED')    AS pending_purchase,
              (SELECT COUNT(*) FROM sales_order    WHERE status='CONFIRMED')    AS pending_sales
            """;

    @Cacheable(cacheNames = CacheHub.DASHBOARD, key = "'summary'")
    public Map<String, Object> summary() {
        LocalDate today = LocalDate.now();
        Timestamp monthStart = Timestamp.valueOf(today.withDayOfMonth(1).atStartOfDay());

        // 4 个参数顺序必须与 METRICS_SQL 中 ? 出现的顺序严格一致
        Map<String, Object> row = db.one(METRICS_SQL, monthStart, monthStart, monthStart, monthStart);
        if (row == null) {
            row = Map.of();
        }

        // 用 LinkedHashMap 保持字段顺序，前端按固定顺序渲染 KPI 卡片
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("monthSales", dec(row, "month_sales"));
        metrics.put("monthPurchase", dec(row, "month_purchase"));
        metrics.put("stockValue", dec(row, "stock_value"));
        metrics.put("monthSalesOrders", num(row, "month_sales_orders"));
        metrics.put("monthPurchaseOrders", num(row, "month_purchase_orders"));
        metrics.put("products", num(row, "products"));
        metrics.put("customers", num(row, "customers"));
        metrics.put("suppliers", num(row, "suppliers"));
        metrics.put("lowStock", num(row, "low_stock"));
        metrics.put("pendingPurchase", num(row, "pending_purchase"));
        metrics.put("pendingSales", num(row, "pending_sales"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metrics", metrics);
        result.put("trend", trend(today.minusDays(6)));
        result.put("topProducts", db.list(
                "SELECT p.name, SUM(i.quantity) AS qty, SUM(i.amount) AS amount "
                        + "FROM sales_item i JOIN sales_order o ON o.id=i.order_id "
                        + "JOIN product p ON p.id=i.product_id "
                        + "WHERE o.status='SHIPPED' GROUP BY p.id, p.name "
                        + "ORDER BY amount DESC LIMIT 5"));
        result.put("lowStockList", db.list(
                "SELECT p.code, p.name, p.unit, p.safety_stock, i.quantity, w.name AS warehouse_name "
                        + "FROM inventory i JOIN product p ON p.id=i.product_id "
                        + "JOIN warehouse w ON w.id=i.warehouse_id "
                        + "WHERE i.quantity <= p.safety_stock ORDER BY i.quantity LIMIT 6"));
        result.put("recentOrders", recentOrders());
        return result;
    }

    /**
     * 按列名取值，兼容不同驱动的列名大小写策略。
     * H2 开了 DATABASE_TO_LOWER=TRUE 返回全小写，MySQL 在部分配置下会保留别名大小写，
     * 所以这里兜底做一次忽略大小写的查找，避免换库后指标全变 0。
     */
    private static Object col(Map<String, Object> row, String name) {
        Object v = row.get(name);
        if (v != null) {
            return v;
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static BigDecimal dec(Map<String, Object> row, String name) {
        Object v = col(row, name);
        return v == null ? BigDecimal.ZERO : Val.decOf(v);
    }

    private static long num(Map<String, Object> row, String name) {
        Object v = col(row, name);
        return v == null ? 0L : Val.decOf(v).longValue();
    }

    private List<Map<String, Object>> trend(LocalDate from) {
        Timestamp fromTs = Timestamp.valueOf(from.atStartOfDay());
        Map<LocalDate, BigDecimal> sales = new HashMap<>();
        Map<LocalDate, BigDecimal> purchase = new HashMap<>();
        for (Map<String, Object> row : db.list(
                "SELECT total_amount, created_at FROM sales_order WHERE status='SHIPPED' AND created_at>=?", fromTs)) {
            LocalDate day = Val.dateOf(row.get("created_at"));
            sales.merge(day, Val.decOf(row.get("total_amount")), BigDecimal::add);
        }
        for (Map<String, Object> row : db.list(
                "SELECT total_amount, created_at FROM purchase_order WHERE status='RECEIVED' AND created_at>=?", fromTs)) {
            LocalDate day = Val.dateOf(row.get("created_at"));
            purchase.merge(day, Val.decOf(row.get("total_amount")), BigDecimal::add);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = from.plusDays(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.format(DateTimeFormatter.ofPattern("MM-dd")));
            point.put("sales", sales.getOrDefault(day, BigDecimal.ZERO));
            point.put("purchase", purchase.getOrDefault(day, BigDecimal.ZERO));
            trend.add(point);
        }
        return trend;
    }

    private List<Map<String, Object>> recentOrders() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : db.list(
                "SELECT o.order_no, o.total_amount, o.status, o.created_at, c.name AS partner_name "
                        + "FROM sales_order o LEFT JOIN customer c ON c.id=o.customer_id "
                        + "ORDER BY o.id DESC LIMIT 5")) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("type", "销售订单");
            rows.add(m);
        }
        for (Map<String, Object> r : db.list(
                "SELECT o.order_no, o.total_amount, o.status, o.created_at, s.name AS partner_name "
                        + "FROM purchase_order o LEFT JOIN supplier s ON s.id=o.supplier_id "
                        + "ORDER BY o.id DESC LIMIT 5")) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("type", "采购订单");
            rows.add(m);
        }
        rows.sort((a, b) -> Val.strOf(b.get("created_at")).compareTo(Val.strOf(a.get("created_at"))));
        // 必须复制成普通 ArrayList，不能直接返回 subList()：
        // subList 返回的是 ArrayList$SubList 内部类视图，它没有无参构造器，
        // Jackson 从 Redis 反序列化缓存时会抛
        // "Cannot construct instance of java.util.ArrayList$SubList"。
        // 进程内存缓存存的是对象引用，看不出问题；一旦切到 Redis（要序列化）就会暴露。
        // 结论：凡是可能被缓存的对象，都应使用可序列化、可反序列化的标准集合类型。
        return rows.size() > 8 ? new ArrayList<>(rows.subList(0, 8)) : rows;
    }
}
