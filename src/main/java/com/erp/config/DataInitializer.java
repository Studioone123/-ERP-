package com.erp.config;

import com.erp.common.Db;
import com.erp.common.PasswordService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 首次启动时写入默认账号与演示数据；已有账号则完全跳过，不会覆盖用户数据。
 */
@Component
@Order(1) // 先建业务演示数据，ModuleInitializer(Order=2) 才能给历史单据补凭证
public class DataInitializer implements ApplicationRunner {

    private final Db db;
    private final PasswordService passwordService;

    public DataInitializer(Db db, PasswordService passwordService) {
        this.db = db;
        this.passwordService = passwordService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer count = db.value("SELECT COUNT(*) FROM sys_user", Integer.class);
        if (count != null && count > 0) {
            return;
        }

        // 默认管理员 admin / admin123，落库前做 BCrypt 慢哈希
        db.exec("INSERT INTO sys_user(username,password,real_name,role,status) VALUES(?,?,?,?,1)",
                "admin", passwordService.encode("admin123"), "系统管理员", "ADMIN");

        long wh1 = warehouse("WH001", "主仓库", "华东区一号库 · A 区");
        long wh2 = warehouse("WH002", "辅料仓", "华东区一号库 · B 区");

        long sup1 = supplier("GYS001", "恒达电子有限公司", "陈经理", "13800001001", "深圳市南山区科技园");
        long sup2 = supplier("GYS002", "宏远五金制品厂", "刘厂长", "13800001002", "东莞市长安镇");
        long sup3 = supplier("GYS003", "云海包装材料", "赵女士", "13800001003", "苏州市工业园区");

        long cus1 = customer("KH001", "星辰科技有限公司", "王先生", "13900002001", "上海市浦东新区");
        long cus2 = customer("KH002", "华信商贸行", "李女士", "13900002002", "杭州市滨江区");
        long cus3 = customer("KH003", "优联零售连锁", "周经理", "13900002003", "南京市鼓楼区");

        List<Object[]> products = List.of(
                new Object[]{"SP001", "轻薄笔记本 Pro14", "数码设备", "14寸/16G/512G", "台", 5200d, 6999d, 10},
                new Object[]{"SP002", "无线鼠标 M2", "外设配件", "蓝牙双模", "个", 45d, 99d, 50},
                new Object[]{"SP003", "机械键盘 K87", "外设配件", "87键红轴", "个", 180d, 349d, 30},
                new Object[]{"SP004", "27寸高清显示器", "数码设备", "2K/75Hz", "台", 780d, 1299d, 15},
                new Object[]{"SP005", "Type-C 扩展坞", "外设配件", "11合1", "个", 95d, 199d, 40},
                new Object[]{"SP006", "人体工学办公椅", "办公家具", "A1 网布款", "把", 320d, 599d, 20},
                new Object[]{"SP007", "A4 复印纸", "办公耗材", "70g/500张", "箱", 88d, 139d, 30},
                new Object[]{"SP008", "六类网线", "办公耗材", "305米/箱", "箱", 210d, 359d, 10}
        );
        long[] pids = new long[products.size()];
        int[] stock = {6, 186, 74, 28, 25, 33, 120, 17}; // SP001、SP005 低于安全库存，用于演示预警
        for (int i = 0; i < products.size(); i++) {
            Object[] p = products.get(i);
            pids[i] = product((String) p[0], (String) p[1], (String) p[2], (String) p[3], (String) p[4],
                    (Double) p[5], (Double) p[6], (Integer) p[7]);
            db.exec("INSERT INTO inventory(warehouse_id,product_id,quantity) VALUES(?,?,?)", wh1, pids[i], stock[i]);
        }
        db.exec("INSERT INTO inventory(warehouse_id,product_id,quantity) VALUES(?,?,?)", wh2, pids[6], 40);
        db.exec("INSERT INTO inventory(warehouse_id,product_id,quantity) VALUES(?,?,?)", wh2, pids[7], 8);

        // 近一周历史业务单据，让经营看板开箱即有数据
        historyPurchase(6, sup1, wh1, pids[0], 20, 5200d);
        historyPurchase(5, sup3, wh2, pids[6], 100, 88d);
        historyPurchase(3, sup2, wh1, pids[5], 30, 320d);
        historyPurchase(1, sup1, wh1, pids[3], 25, 780d);

        historySale(6, cus1, wh1, pids[0], 3, 6999d);
        historySale(5, cus2, wh1, pids[2], 12, 349d);
        historySale(4, cus3, wh1, pids[1], 40, 99d);
        historySale(3, cus1, wh1, pids[3], 5, 1299d);
        historySale(2, cus2, wh2, pids[6], 15, 139d);
        historySale(1, cus3, wh1, pids[4], 18, 199d);

        db.exec("INSERT INTO sys_log(username,action,detail,ip) VALUES(?,?,?,?)",
                "system", "系统初始化", "已载入默认账号与演示数据", "LOCAL");
    }

    private long warehouse(String code, String name, String address) {
        return db.insert("INSERT INTO warehouse(code,name,address,status) VALUES(?,?,?,1)", code, name, address);
    }

    private long supplier(String code, String name, String contact, String phone, String address) {
        return db.insert("INSERT INTO supplier(code,name,contact,phone,address,status) VALUES(?,?,?,?,?,1)",
                code, name, contact, phone, address);
    }

    private long customer(String code, String name, String contact, String phone, String address) {
        return db.insert("INSERT INTO customer(code,name,contact,phone,address,status) VALUES(?,?,?,?,?,1)",
                code, name, contact, phone, address);
    }

    private long product(String code, String name, String category, String spec, String unit,
                         double cost, double price, int safety) {
        return db.insert("INSERT INTO product(code,name,category,spec,unit,cost_price,sale_price,safety_stock,status) "
                        + "VALUES(?,?,?,?,?,?,?,?,1)",
                code, name, category, spec, unit, cost, price, safety);
    }

    private Timestamp daysAgo(int days) {
        return Timestamp.valueOf(LocalDateTime.now().minusDays(days)
                .withHour(10).withMinute(30).withSecond(0).withNano(0));
    }

    private String historyNo(String prefix, int days, int seq) {
        return prefix + LocalDateTime.now().minusDays(days).toLocalDate().toString().replace("-", "")
                + "-9" + String.format("%03d", seq);
    }

    private void historyPurchase(int days, long supplierId, long whId, long productId, int qty, double price) {
        String no = historyNo("PO", days, (int) productId * 10 + days);
        Timestamp ts = daysAgo(days);
        long id = db.insert("INSERT INTO purchase_order(order_no,supplier_id,warehouse_id,total_amount,status,remark,created_by,created_at,confirm_at,finish_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                no, supplierId, whId, qty * price, "RECEIVED", "历史演示单据", "admin", ts, ts, ts);
        db.exec("INSERT INTO purchase_item(order_id,product_id,quantity,unit_price,amount,received_qty) VALUES(?,?,?,?,?,?)",
                id, productId, qty, price, qty * price, qty);
        flow("PURCHASE_IN", no, whId, productId, qty, 0, qty, ts);
    }

    private void historySale(int days, long customerId, long whId, long productId, int qty, double price) {
        String no = historyNo("SO", days, (int) productId * 10 + days);
        Timestamp ts = daysAgo(days);
        long id = db.insert("INSERT INTO sales_order(order_no,customer_id,warehouse_id,total_amount,status,remark,created_by,created_at,confirm_at,finish_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                no, customerId, whId, qty * price, "SHIPPED", "历史演示单据", "admin", ts, ts, ts);
        db.exec("INSERT INTO sales_item(order_id,product_id,quantity,unit_price,amount,shipped_qty) VALUES(?,?,?,?,?,?)",
                id, productId, qty, price, qty * price, qty);
        flow("SALE_OUT", no, whId, productId, -qty, qty + 20, 20, ts);
    }

    private void flow(String bizType, String bizNo, long whId, long productId,
                      int change, int before, int after, Timestamp ts) {
        db.exec("INSERT INTO stock_flow(flow_no,biz_type,biz_no,warehouse_id,product_id,change_qty,before_qty,after_qty,operator,remark,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                "FL" + bizNo, bizType, bizNo, whId, productId, change, before, after,
                "admin", "历史演示流水", ts);
    }
}
