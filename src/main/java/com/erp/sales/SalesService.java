package com.erp.sales;

import com.erp.common.*;
import com.erp.inventory.StockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** 销售订单：草稿 -> 已审核 -> 已发货（发货时扣减库存并写流水，库存不足则整单回滚） */
@Service
public class SalesService {

    private final Db db;
    private final NoGenerator noGenerator;
    private final StockService stockService;
    private final LogService logService;
    private final BizPostingService posting; // 业财一体化：出库同时生成应收单与两张凭证

    public SalesService(Db db, NoGenerator noGenerator, StockService stockService,
                        LogService logService, BizPostingService posting) {
        this.db = db;
        this.noGenerator = noGenerator;
        this.stockService = stockService;
        this.logService = logService;
        this.posting = posting;
    }

    public List<Map<String, Object>> list(String status, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT o.*, c.name AS customer_name, w.name AS warehouse_name, "
                        + "(SELECT COUNT(*) FROM sales_item i WHERE i.order_id=o.id) AS item_count "
                        + "FROM sales_order o "
                        + "LEFT JOIN customer c ON c.id=o.customer_id "
                        + "LEFT JOIN warehouse w ON w.id=o.warehouse_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND o.status=?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (o.order_no LIKE ? OR c.name LIKE ? OR o.remark LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            Collections.addAll(args, kw, kw, kw);
        }
        sql.append(" ORDER BY o.id DESC LIMIT 300");
        return db.list(sql.toString(), args.toArray());
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> order = require(id);
        List<Map<String, Object>> items = db.list(
                "SELECT i.*, p.code AS product_code, p.name AS product_name, p.unit "
                        + "FROM sales_item i LEFT JOIN product p ON p.id=i.product_id "
                        + "WHERE i.order_id=? ORDER BY i.id", id);
        Map<String, Object> result = new LinkedHashMap<>(order);
        result.put("items", items);
        return result;
    }

    @Transactional
    public long create(Map<String, Object> body) {
        long customerId = Db.lng(body, "customer_id", 0);
        long warehouseId = Db.lng(body, "warehouse_id", 0);
        requireExists("customer", customerId, "客户");
        requireExists("warehouse", warehouseId, "仓库");

        List<Map<String, Object>> items = parseItems(body);
        String orderNo = noGenerator.sales();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> item : items) {
            total = total.add(Db.decimal(item, "unit_price")
                    .multiply(BigDecimal.valueOf(Db.integer(item, "quantity", 0))));
        }

        long id = db.insert("INSERT INTO sales_order(order_no,customer_id,warehouse_id,total_amount,"
                        + "status,remark,created_by,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                orderNo, customerId, warehouseId, total, "DRAFT", Db.str(body, "remark"),
                UserContext.get() == null ? "admin" : UserContext.get());
        for (Map<String, Object> item : items) {
            long productId = Db.lng(item, "product_id", 0);
            int qty = Db.integer(item, "quantity", 0);
            BigDecimal price = Db.decimal(item, "unit_price");
            db.exec("INSERT INTO sales_item(order_id,product_id,quantity,unit_price,amount) VALUES(?,?,?,?,?)",
                    id, productId, qty, price, price.multiply(BigDecimal.valueOf(qty)));
        }
        logService.record("新增销售订单", orderNo + "，共 " + items.size() + " 项，金额 " + total);
        return id;
    }

    @Transactional
    public void confirm(long id) {
        Map<String, Object> order = require(id);
        assertStatus(order, "DRAFT", "只有草稿状态的订单可以审核");
        db.exec("UPDATE sales_order SET status='CONFIRMED', confirm_at=CURRENT_TIMESTAMP WHERE id=?", id);
        logService.record("审核销售订单", Val.strOf(order.get("order_no")));
    }

    /**
     * 发货出库 —— 同一事务内完成四件事：
     * <ol>
     *   <li>按条件扣减库存（不足则整单回滚，防超卖）并写流水</li>
     *   <li>生成应收单 AR</li>
     *   <li>凭证一：确认收入（借 应收账款 / 贷 主营业务收入）</li>
     *   <li>凭证二：结转成本（借 主营业务成本 / 贷 库存商品）</li>
     * </ol>
     * 成本按「商品档案成本价 × 出库数量」计算，见 BizPostingService.salesCost。
     */
    @Transactional
    public void ship(long id) {
        Map<String, Object> order = require(id);
        assertStatus(order, "CONFIRMED", "只有已审核的订单才能发货出库");
        long warehouseId = Val.longOf(order.get("warehouse_id"));
        String orderNo = Val.strOf(order.get("order_no"));
        List<Map<String, Object>> items = db.list("SELECT * FROM sales_item WHERE order_id=?", id);
        for (Map<String, Object> item : items) {
            int qty = Val.intOf(item.get("quantity")) - Val.intOf(item.get("shipped_qty"));
            if (qty <= 0) {
                continue;
            }
            stockService.change(warehouseId, Val.longOf(item.get("product_id")), -qty,
                    "SALE_OUT", orderNo, "销售发货出库 " + orderNo);
            db.exec("UPDATE sales_item SET shipped_qty=shipped_qty+? WHERE id=?",
                    qty, Val.longOf(item.get("id")));
        }
        db.exec("UPDATE sales_order SET status='SHIPPED', finish_at=CURRENT_TIMESTAMP WHERE id=?", id);

        // ↓ 业财一体化：收入确认 + 成本结转两张凭证，外加一张应收单
        Map<String, Object> partner = db.one(
                "SELECT c.name FROM customer c JOIN sales_order o ON o.customer_id=c.id WHERE o.id=?", id);
        String customerName = partner == null ? "" : Val.strOf(partner.get("name"));
        posting.salesOut(orderNo, Val.longOf(order.get("customer_id")), customerName,
                Val.decOf(order.get("total_amount")), posting.salesCost(orderNo),
                java.time.LocalDate.now());

        logService.record("销售出库", orderNo + " 已完成发货，已同步生成应收单与收入/成本凭证");
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> order = require(id);
        String status = Val.strOf(order.get("status"));
        if (!"DRAFT".equals(status) && !"CONFIRMED".equals(status)) {
            throw new BizException("已发货或已取消的订单不能再取消");
        }
        db.exec("UPDATE sales_order SET status='CANCELLED' WHERE id=?", id);
        logService.record("取消销售订单", Val.strOf(order.get("order_no")));
    }

    private List<Map<String, Object>> parseItems(Map<String, Object> body) {
        Object raw = body.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new BizException("请至少添加一条销售明细");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new BizException("明细格式不正确");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) m;
            long productId = Db.lng(item, "product_id", 0);
            int qty = Db.integer(item, "quantity", 0);
            BigDecimal price = Db.decimal(item, "unit_price");
            if (productId <= 0) {
                throw new BizException("明细中存在未选择的商品");
            }
            if (qty <= 0) {
                throw new BizException("销售数量必须大于 0");
            }
            if (price.signum() < 0) {
                throw new BizException("销售单价不能为负数");
            }
            requireExists("product", productId, "商品");
            items.add(item);
        }
        return items;
    }

    private void requireExists(String table, long id, String label) {
        if (id <= 0) {
            throw new BizException("请选择" + label);
        }
        Map<String, Object> row = db.one("SELECT id FROM " + table + " WHERE id=? AND status=1", id);
        if (row == null) {
            throw new BizException(label + "不存在或已停用");
        }
    }

    private Map<String, Object> require(long id) {
        Map<String, Object> row = db.one("SELECT * FROM sales_order WHERE id=?", id);
        if (row == null) {
            throw new BizException("销售订单不存在");
        }
        return row;
    }

    private void assertStatus(Map<String, Object> order, String expected, String message) {
        if (!expected.equals(Val.strOf(order.get("status")))) {
            throw new BizException(message);
        }
    }
}
