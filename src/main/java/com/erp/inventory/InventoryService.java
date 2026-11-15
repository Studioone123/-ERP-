package com.erp.inventory;

import com.erp.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class InventoryService {

    private final Db db;
    private final StockService stockService;
    private final LogService logService;

    public InventoryService(Db db, StockService stockService, LogService logService) {
        this.db = db;
        this.stockService = stockService;
        this.logService = logService;
    }

    /** 实时库存，低于安全库存的排在最前面 */
    public List<Map<String, Object>> stock(String warehouseId, String keyword, boolean alertOnly) {
        StringBuilder sql = new StringBuilder(
                "SELECT i.id, i.warehouse_id, w.name AS warehouse_name, i.product_id, "
                        + "p.code, p.name AS product_name, p.category, p.unit, p.cost_price, "
                        + "p.safety_stock, i.quantity, i.quantity * p.cost_price AS stock_value, "
                        + "i.updated_at, "
                        + "CASE WHEN i.quantity <= p.safety_stock THEN 1 ELSE 0 END AS alert "
                        + "FROM inventory i "
                        + "JOIN warehouse w ON w.id=i.warehouse_id "
                        + "JOIN product p ON p.id=i.product_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (warehouseId != null && !warehouseId.isBlank()) {
            sql.append(" AND i.warehouse_id=?");
            args.add(Long.parseLong(warehouseId));
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (p.code LIKE ? OR p.name LIKE ? OR p.category LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            Collections.addAll(args, kw, kw, kw);
        }
        if (alertOnly) {
            sql.append(" AND i.quantity <= p.safety_stock");
        }
        sql.append(" ORDER BY alert DESC, i.warehouse_id, p.id");
        return db.list(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> flows(String bizType, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT f.*, w.name AS warehouse_name, p.code, p.name AS product_name, p.unit "
                        + "FROM stock_flow f "
                        + "LEFT JOIN warehouse w ON w.id=f.warehouse_id "
                        + "LEFT JOIN product p ON p.id=f.product_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (bizType != null && !bizType.isBlank()) {
            sql.append(" AND f.biz_type=?");
            args.add(bizType);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (f.biz_no LIKE ? OR p.name LIKE ? OR p.code LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            Collections.addAll(args, kw, kw, kw);
        }
        sql.append(" ORDER BY f.id DESC LIMIT 300");
        return db.list(sql.toString(), args.toArray());
    }

    /** 库存盘点：把库存调整到指定数量，差额自动写入流水 */
    @Transactional
    public int adjust(Map<String, Object> body) {
        long warehouseId = Db.lng(body, "warehouse_id", 0);
        long productId = Db.lng(body, "product_id", 0);
        if (warehouseId <= 0 || productId <= 0) {
            throw new BizException("请选择仓库和商品");
        }
        if (db.one("SELECT id FROM product WHERE id=?", productId) == null
                || db.one("SELECT id FROM warehouse WHERE id=?", warehouseId) == null) {
            throw new BizException("商品或仓库不存在");
        }
        if (!body.containsKey("quantity")) {
            throw new BizException("请输入盘点后的数量");
        }
        int target = Db.integer(body, "quantity", -1);
        if (target < 0) {
            throw new BizException("库存数量不能为负数");
        }
        int current = stockService.current(warehouseId, productId);
        String bizNo = "PD" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + System.currentTimeMillis() % 10000;
        int after = stockService.change(warehouseId, productId, target - current,
                "ADJUST", bizNo, "库存盘点：" + Db.str(body, "remark"));
        logService.record("库存盘点", bizNo + " 数量 " + current + " -> " + after);
        return after;
    }
}
