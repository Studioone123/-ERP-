package com.erp.inventory;

import com.erp.common.BizException;
import com.erp.common.Db;
import com.erp.common.NoGenerator;
import com.erp.common.UserContext;
import com.erp.common.Val;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 库存变更的唯一入口：所有出入库都必须经过这里，保证「库存 + 流水」成对写入。
 * 出库使用带条件的 UPDATE（quantity >= 出库量），避免并发下超卖。
 */
@Service
public class StockService {

    private final Db db;
    private final NoGenerator noGenerator;

    public StockService(Db db, NoGenerator noGenerator) {
        this.db = db;
        this.noGenerator = noGenerator;
    }

    public int current(long warehouseId, long productId) {
        Integer qty = db.value("SELECT quantity FROM inventory WHERE warehouse_id=? AND product_id=?",
                Integer.class, warehouseId, productId);
        return qty == null ? 0 : qty;
    }

    /**
     * @param delta 正数入库、负数出库
     * @return 变更后的库存数量
     */
    public int change(long warehouseId, long productId, int delta,
                      String bizType, String bizNo, String remark) {
        if (delta == 0) {
            return current(warehouseId, productId);
        }
        ensureRow(warehouseId, productId);

        if (delta < 0) {
            int rows = db.exec("UPDATE inventory SET quantity=quantity+?, updated_at=CURRENT_TIMESTAMP "
                            + "WHERE warehouse_id=? AND product_id=? AND quantity>=?",
                    delta, warehouseId, productId, -delta);
            if (rows == 0) {
                Map<String, Object> p = db.one("SELECT name FROM product WHERE id=?", productId);
                String name = p == null ? ("ID=" + productId) : Val.strOf(p.get("name"));
                throw new BizException("商品【" + name + "】库存不足，无法出库");
            }
        } else {
            db.exec("UPDATE inventory SET quantity=quantity+?, updated_at=CURRENT_TIMESTAMP "
                    + "WHERE warehouse_id=? AND product_id=?", delta, warehouseId, productId);
        }

        int after = current(warehouseId, productId);
        db.exec("INSERT INTO stock_flow(flow_no,biz_type,biz_no,warehouse_id,product_id,"
                        + "change_qty,before_qty,after_qty,operator,remark,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                noGenerator.flow(), bizType, bizNo, warehouseId, productId,
                delta, after - delta, after,
                UserContext.get() == null ? "system" : UserContext.get(),
                remark == null ? "" : remark);
        return after;
    }

    private void ensureRow(long warehouseId, long productId) {
        Integer c = db.value("SELECT COUNT(*) FROM inventory WHERE warehouse_id=? AND product_id=?",
                Integer.class, warehouseId, productId);
        if (c == null || c == 0) {
            db.exec("INSERT INTO inventory(warehouse_id,product_id,quantity) VALUES(?,?,0)",
                    warehouseId, productId);
        }
    }
}
