package com.erp.purchase;

import com.erp.common.*;
import com.erp.inventory.StockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** 采购订单：草稿 -> 已审核 -> 已入库（入库时增加库存并写流水） */
@Service
public class PurchaseService {

    private final Db db;
    private final NoGenerator noGenerator;
    private final StockService stockService;
    private final LogService logService;
    private final BizPostingService posting; // 业财一体化：入库同时生成应付单与凭证

    public PurchaseService(Db db, NoGenerator noGenerator, StockService stockService,
                           LogService logService, BizPostingService posting) {
        this.db = db;
        this.noGenerator = noGenerator;
        this.stockService = stockService;
        this.logService = logService;
        this.posting = posting;
    }

    public List<Map<String, Object>> list(String status, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT o.*, s.name AS supplier_name, w.name AS warehouse_name, "
                        + "(SELECT COUNT(*) FROM purchase_item i WHERE i.order_id=o.id) AS item_count "
                        + "FROM purchase_order o "
                        + "LEFT JOIN supplier s ON s.id=o.supplier_id "
                        + "LEFT JOIN warehouse w ON w.id=o.warehouse_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND o.status=?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (o.order_no LIKE ? OR s.name LIKE ? OR o.remark LIKE ?)");
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
                        + "FROM purchase_item i LEFT JOIN product p ON p.id=i.product_id "
                        + "WHERE i.order_id=? ORDER BY i.id", id);
        Map<String, Object> result = new LinkedHashMap<>(order);
        result.put("items", items);
        return result;
    }

    @Transactional
    public long create(Map<String, Object> body) {
        long supplierId = Db.lng(body, "supplier_id", 0);
        long warehouseId = Db.lng(body, "warehouse_id", 0);
        requireExists("supplier", supplierId, "供应商");
        requireExists("warehouse", warehouseId, "仓库");

        List<Map<String, Object>> items = parseItems(body);
        String orderNo = noGenerator.purchase();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> item : items) {
            total = total.add(Db.decimal(item, "unit_price")
                    .multiply(BigDecimal.valueOf(Db.integer(item, "quantity", 0))));
        }

        long id = db.insert("INSERT INTO purchase_order(order_no,supplier_id,warehouse_id,total_amount,"
                        + "status,remark,created_by,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                orderNo, supplierId, warehouseId, total, "DRAFT", Db.str(body, "remark"),
                UserContext.get() == null ? "admin" : UserContext.get());
        for (Map<String, Object> item : items) {
            long productId = Db.lng(item, "product_id", 0);
            int qty = Db.integer(item, "quantity", 0);
            BigDecimal price = Db.decimal(item, "unit_price");
            db.exec("INSERT INTO purchase_item(order_id,product_id,quantity,unit_price,amount) VALUES(?,?,?,?,?)",
                    id, productId, qty, price, price.multiply(BigDecimal.valueOf(qty)));
        }
        logService.record("新增采购订单", orderNo + "，共 " + items.size() + " 项，金额 " + total);
        return id;
    }

    @Transactional
    public void confirm(long id) {
        Map<String, Object> order = require(id);
        assertStatus(order, "DRAFT", "只有草稿状态的订单可以审核");
        db.exec("UPDATE purchase_order SET status='CONFIRMED', confirm_at=CURRENT_TIMESTAMP WHERE id=?", id);
        logService.record("审核采购订单", Val.strOf(order.get("order_no")));
    }

    /**
     * 收货入库 —— 借鉴金蝶「单据执行即产生三本账」的设计，同一事务内完成：
     * <ol>
     *   <li>库存 +N 并写库存流水（StockService）</li>
     *   <li>生成应付单 AP，供后续付款核销</li>
     *   <li>生成会计凭证：借 库存商品 / 贷 应付账款</li>
     * </ol>
     * 任一步失败全部回滚，绝不会出现「货收了、账没记」的中间状态。
     */
    @Transactional
    public void receive(long id) {
        Map<String, Object> order = require(id);
        assertStatus(order, "CONFIRMED", "只有已审核的订单才能收货入库");
        long warehouseId = Val.longOf(order.get("warehouse_id"));
        String orderNo = Val.strOf(order.get("order_no"));
        List<Map<String, Object>> items = db.list("SELECT * FROM purchase_item WHERE order_id=?", id);
        for (Map<String, Object> item : items) {
            int qty = Val.intOf(item.get("quantity")) - Val.intOf(item.get("received_qty"));
            if (qty <= 0) {
                continue;
            }
            stockService.change(warehouseId, Val.longOf(item.get("product_id")), qty,
                    "PURCHASE_IN", orderNo, "采购收货入库 " + orderNo);
            db.exec("UPDATE purchase_item SET received_qty=received_qty+? WHERE id=?",
                    qty, Val.longOf(item.get("id")));
        }
        db.exec("UPDATE purchase_order SET status='RECEIVED', finish_at=CURRENT_TIMESTAMP WHERE id=?", id);

        // ↓ 业财一体化：查供应商名称后交给凭证模板生成「应付单 + 会计凭证」
        //   金额取订单总额，业务日期取当天；金额 <= 0 时模板内部会自动跳过。
        Map<String, Object> partner = db.one(
                "SELECT s.name FROM supplier s JOIN purchase_order o ON o.supplier_id=s.id WHERE o.id=?", id);
        String supplierName = partner == null ? "" : Val.strOf(partner.get("name"));
        long supplierId = Val.longOf(order.get("supplier_id"));
        posting.purchaseIn(orderNo, supplierId, supplierName,
                Val.decOf(order.get("total_amount")), java.time.LocalDate.now());

        logService.record("采购入库", orderNo + " 已完成入库，已同步生成应付单与记账凭证");
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> order = require(id);
        String status = Val.strOf(order.get("status"));
        if (!"DRAFT".equals(status) && !"CONFIRMED".equals(status)) {
            throw new BizException("已入库或已取消的订单不能再取消");
        }
        db.exec("UPDATE purchase_order SET status='CANCELLED' WHERE id=?", id);
        logService.record("取消采购订单", Val.strOf(order.get("order_no")));
    }

    private List<Map<String, Object>> parseItems(Map<String, Object> body) {
        Object raw = body.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new BizException("请至少添加一条采购明细");
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
                throw new BizException("采购数量必须大于 0");
            }
            if (price.signum() < 0) {
                throw new BizException("采购单价不能为负数");
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
        Map<String, Object> row = db.one("SELECT * FROM purchase_order WHERE id=?", id);
        if (row == null) {
            throw new BizException("采购订单不存在");
        }
        return row;
    }

    private void assertStatus(Map<String, Object> order, String expected, String message) {
        if (!expected.equals(Val.strOf(order.get("status")))) {
            throw new BizException(message);
        }
    }
}
