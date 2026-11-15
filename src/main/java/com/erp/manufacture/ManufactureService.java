package com.erp.manufacture;

import com.erp.common.*;
import com.erp.inventory.StockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 生产任务管理 —— 对应金蝶 K/3「生产任务管理 → 生产任务单 / 领料 / 完工入库」。
 *
 * <h3>状态机</h3>
 * <pre>
 *   DRAFT 草稿 → CONFIRMED 已审核 → ISSUED 已领料 → FINISHED 已完工
 *                    ↓
 *                CANCELLED 已取消（仅草稿 / 已审核可取消）
 * </pre>
 *
 * <h3>两次库存动作 + 两次凭证</h3>
 * <pre>
 *   领料：按 BOM 展开子件，逐个扣减原材料库存
 *         借 5001 生产成本 / 贷 1403 原材料
 *   完工：产成品按计划数量入库
 *         借 1405 库存商品 / 贷 5001 生产成本
 * </pre>
 * 领料与完工都和库存、总账在同一事务里，任一子件库存不足会整体回滚，
 * 不会出现「料领了一半、任务卡在中间」的脏状态。
 */
@Service
public class ManufactureService {

    private final Db db;
    private final NoGenerator noGenerator;
    private final StockService stockService;
    private final BizPostingService posting;
    private final LogService logService;

    public ManufactureService(Db db, NoGenerator noGenerator, StockService stockService,
                              BizPostingService posting, LogService logService) {
        this.db = db;
        this.noGenerator = noGenerator;
        this.stockService = stockService;
        this.posting = posting;
        this.logService = logService;
    }

    /** 生产任务列表，带成品名称、BOM 编码、仓库名称与完工进度 */
    public List<Map<String, Object>> list(String status, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT o.*, p.name AS product_name, p.unit, p.cost_price, "
                        + "b.bom_code, w.name AS warehouse_name "
                        + "FROM mf_order o LEFT JOIN product p ON p.id=o.product_id "
                        + "LEFT JOIN mf_bom b ON b.id=o.bom_id "
                        + "LEFT JOIN warehouse w ON w.id=o.warehouse_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND o.status=?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (o.order_no LIKE ? OR p.name LIKE ? OR b.bom_code LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            Collections.addAll(args, kw, kw, kw);
        }
        sql.append(" ORDER BY o.id DESC LIMIT 300");
        return db.list(sql.toString(), args.toArray());
    }

    /** 详情：任务主表 + BOM 子件展开（含计划领用量），供领料前预览 */
    public Map<String, Object> detail(long id) {
        Map<String, Object> order = require(id);
        Map<String, Object> result = new LinkedHashMap<>(order);
        int qty = Val.intOf(order.get("quantity"));
        long bomId = Val.longOf(order.get("bom_id"));
        List<Map<String, Object>> items = new ArrayList<>();
        if (bomId > 0) {
            for (Map<String, Object> i : db.list(
                    "SELECT i.*, p.code AS material_code, p.name AS material_name, p.unit, p.cost_price "
                            + "FROM mf_bom_item i LEFT JOIN product p ON p.id=i.material_id "
                            + "WHERE i.bom_id=? ORDER BY i.id", bomId)) {
                Map<String, Object> row = new LinkedHashMap<>(i);
                row.put("plan_qty", needQty(Val.intOf(i.get("quantity")), qty,
                        Val.decOf(i.get("loss_rate"))));
                items.add(row);
            }
        }
        result.put("items", items);
        return result;
    }

    /** 新建生产任务单：必须指定已启用的成品、有效 BOM 与仓库 */
    @Transactional
    public long create(Map<String, Object> body) {
        long productId = Db.lng(body, "product_id", 0);
        long bomId = Db.lng(body, "bom_id", 0);
        long warehouseId = Db.lng(body, "warehouse_id", 0);
        int qty = Db.integer(body, "quantity", 0);
        if (qty <= 0) {
            throw new BizException("计划生产数量必须大于 0");
        }
        requireActive("product", productId, "产成品");
        requireActive("warehouse", warehouseId, "仓库");
        Map<String, Object> bom = db.one("SELECT * FROM mf_bom WHERE id=? AND status=1", bomId);
        if (bom == null) {
            throw new BizException("请选择有效的 BOM");
        }
        // BOM 必须对应同一个成品，避免张冠李戴
        if (Val.longOf(bom.get("product_id")) != productId) {
            throw new BizException("所选 BOM 不属于该产成品，请重新选择");
        }
        Integer itemCount = db.value("SELECT COUNT(*) FROM mf_bom_item WHERE bom_id=?", Integer.class, bomId);
        if (itemCount == null || itemCount == 0) {
            throw new BizException("该 BOM 没有子件，无法安排生产");
        }
        long id = db.insert("INSERT INTO mf_order(order_no,product_id,bom_id,warehouse_id,quantity,"
                        + "finished_qty,status,remark,created_by,created_at) "
                        + "VALUES(?,?,?,?,?,0,'DRAFT',?,?,CURRENT_TIMESTAMP)",
                noGenerator.manufacture(), productId, bomId, warehouseId, qty, Db.str(body, "remark"),
                UserContext.get() == null ? "admin" : UserContext.get());
        logService.record("新增生产任务", "数量 " + qty + "，BOM ID " + bomId);
        return id;
    }

    @Transactional
    public void confirm(long id) {
        Map<String, Object> order = require(id);
        assertStatus(order, "DRAFT", "只有草稿状态的任务可以审核");
        db.exec("UPDATE mf_order SET status='CONFIRMED', confirm_at=CURRENT_TIMESTAMP WHERE id=?", id);
        logService.record("审核生产任务", Val.strOf(order.get("order_no")));
    }

    /**
     * 按 BOM 领料出库。
     * 应领数量 = 单位用量 × 计划产量 × (1 + 损耗率%)，向上取整（物料按整件领用）。
     */
    @Transactional
    public void issue(long id) {
        Map<String, Object> order = require(id);
        assertStatus(order, "CONFIRMED", "只有已审核的任务才能领料，且不能重复领料");
        long warehouseId = Val.longOf(order.get("warehouse_id"));
        long bomId = Val.longOf(order.get("bom_id"));
        int planQty = Val.intOf(order.get("quantity"));
        String orderNo = Val.strOf(order.get("order_no"));

        BigDecimal materialAmount = BigDecimal.ZERO;
        List<Map<String, Object>> items = db.list(
                "SELECT i.*, p.name AS material_name, p.cost_price FROM mf_bom_item i "
                        + "LEFT JOIN product p ON p.id=i.material_id WHERE i.bom_id=?", bomId);
        if (items.isEmpty()) {
            throw new BizException("BOM 中没有子件，无法领料");
        }
        for (Map<String, Object> item : items) {
            int need = needQty(Val.intOf(item.get("quantity")), planQty, Val.decOf(item.get("loss_rate")));
            long materialId = Val.longOf(item.get("material_id"));
            // StockService 内部带条件扣减，库存不足会抛异常并让整个事务回滚
            stockService.change(warehouseId, materialId, -need, "MATERIAL_ISSUE", orderNo,
                    "生产领料 " + orderNo + " / " + Val.strOf(item.get("material_name")));
            materialAmount = materialAmount.add(
                    Val.decOf(item.get("cost_price")).multiply(BigDecimal.valueOf(need)));
        }
        db.exec("UPDATE mf_order SET status='ISSUED', issue_at=CURRENT_TIMESTAMP WHERE id=?", id);
        posting.materialIssue(orderNo, materialAmount, LocalDate.now());
        logService.record("生产领料", orderNo + " 领用 " + items.size() + " 种物料，成本 " + materialAmount);
    }

    /** 完工入库：产成品按计划数量入库，并按成本价结转生产成本 */
    @Transactional
    public void finish(long id) {
        Map<String, Object> order = require(id);
        assertStatus(order, "ISSUED", "只有已领料的任务才能完工入库");
        int qty = Val.intOf(order.get("quantity")) - Val.intOf(order.get("finished_qty"));
        if (qty <= 0) {
            throw new BizException("没有待完工数量");
        }
        long productId = Val.longOf(order.get("product_id"));
        long warehouseId = Val.longOf(order.get("warehouse_id"));
        String orderNo = Val.strOf(order.get("order_no"));
        stockService.change(warehouseId, productId, qty, "PRODUCT_FINISH", orderNo,
                "生产完工入库 " + orderNo);

        Map<String, Object> product = db.one("SELECT name, cost_price FROM product WHERE id=?", productId);
        BigDecimal unitCost = product == null ? BigDecimal.ZERO : Val.decOf(product.get("cost_price"));
        BigDecimal amount = unitCost.multiply(BigDecimal.valueOf(qty));
        db.exec("UPDATE mf_order SET status='FINISHED', finished_qty=finished_qty+?, "
                + "finish_at=CURRENT_TIMESTAMP WHERE id=?", qty, id);
        posting.productFinish(orderNo, amount, LocalDate.now());
        logService.record("生产完工入库", orderNo + " 完工 " + qty + " 件，入库成本 " + amount);
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> order = require(id);
        String status = Val.strOf(order.get("status"));
        if (!"DRAFT".equals(status) && !"CONFIRMED".equals(status)) {
            throw new BizException("已领料或已完工的任务不能取消");
        }
        db.exec("UPDATE mf_order SET status='CANCELLED' WHERE id=?", id);
        logService.record("取消生产任务", Val.strOf(order.get("order_no")));
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /** 应领数量 = 单位用量 × 产量 × (1 + 损耗率/100)，向上取整 */
    private static int needQty(int unitQty, int planQty, BigDecimal lossRate) {
        BigDecimal factor = BigDecimal.ONE.add(
                lossRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        return BigDecimal.valueOf((long) unitQty * planQty)
                .multiply(factor).setScale(0, RoundingMode.CEILING).intValue();
    }

    private void requireActive(String table, long id, String label) {
        if (id <= 0 || db.one("SELECT id FROM " + table + " WHERE id=? AND status=1", id) == null) {
            throw new BizException("请选择有效的" + label);
        }
    }

    private Map<String, Object> require(long id) {
        Map<String, Object> row = db.one("SELECT * FROM mf_order WHERE id=?", id);
        if (row == null) {
            throw new BizException("生产任务不存在");
        }
        return row;
    }

    private void assertStatus(Map<String, Object> order, String expected, String message) {
        if (!expected.equals(Val.strOf(order.get("status")))) {
            throw new BizException(message);
        }
    }
}
