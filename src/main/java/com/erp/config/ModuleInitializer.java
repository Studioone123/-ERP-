package com.erp.config;

import com.erp.common.BizPostingService;
import com.erp.common.Db;
import com.erp.common.Val;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 扩展模块初始化器 —— 负责总账 / 往来 / 制造 / 组织档案的基础数据。
 *
 * <h3>为什么不直接写进 DataInitializer</h3>
 * {@link DataInitializer} 开头有一句「sys_user 有数据就整个跳过」，这是为了不覆盖用户数据。
 * 但本次是<b>给已有数据库加模块</b>：老库的 sys_user 一定非空，如果新数据写在那里面，
 * 用户重启后会发现科目表是空的、凭证页面没数据。
 * 因此这里独立成一个 Runner，并且<b>每类数据各自判空</b>，互不影响：
 * <pre>
 *   科目表空 → 灌科目      部门表空 → 灌部门
 *   凭证表空 → 补历史单据凭证 + 期初建账
 * </pre>
 * 这个「按数据集独立幂等初始化」的写法，也是数据库升级脚本的常用套路。
 *
 * <p>{@code @Order(2)} 保证它排在 DataInitializer（Order=1）之后，
 * 这样历史采购 / 销售单据已经存在，才能给它们补凭证。
 */
@Component
@Order(2)
public class ModuleInitializer implements ApplicationRunner {

    private final Db db;
    private final BizPostingService posting;

    public ModuleInitializer(Db db, BizPostingService posting) {
        this.db = db;
        this.posting = posting;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        initSubjects();
        initOrganization();
        initPeriods();
        initBomAndMaterials();
        backfillHistoryVouchers();
        initOpeningVoucher();
    }

    // =====================================================================
    // 1. 会计科目（金蝶「科目体系」，编码沿用国内会计制度常用 4 位码）
    // =====================================================================
    private void initSubjects() {
        if (count("fin_subject") > 0) {
            return;
        }
        // 字段：编码、名称、类别、余额方向、上级、是否末级、备注
        List<Object[]> subjects = List.of(
                new Object[]{"1001", "库存现金", "ASSET", "DEBIT", "", 1, "资产类"},
                new Object[]{"1002", "银行存款", "ASSET", "DEBIT", "", 1, "资产类"},
                new Object[]{"1122", "应收账款", "ASSET", "DEBIT", "", 1, "资产类 · 销售自动记账"},
                new Object[]{"1403", "原材料", "ASSET", "DEBIT", "", 1, "资产类 · 生产领料"},
                new Object[]{"1405", "库存商品", "ASSET", "DEBIT", "", 1, "资产类 · 采购入库 / 销售结转"},
                new Object[]{"2202", "应付账款", "LIABILITY", "CREDIT", "", 1, "负债类 · 采购自动记账"},
                new Object[]{"4001", "实收资本", "EQUITY", "CREDIT", "", 1, "所有者权益"},
                new Object[]{"4103", "本年利润", "EQUITY", "CREDIT", "", 1, "所有者权益 · 报表自动计算"},
                new Object[]{"5001", "生产成本", "COST", "DEBIT", "", 1, "成本类 · 期末余额为在制品"},
                new Object[]{"6001", "主营业务收入", "INCOME", "CREDIT", "", 1, "损益类 · 销售自动记账"},
                new Object[]{"6401", "主营业务成本", "EXPENSE", "DEBIT", "", 1, "损益类 · 结转成本"},
                new Object[]{"6601", "销售费用", "EXPENSE", "DEBIT", "", 1, "损益类"},
                new Object[]{"6602", "管理费用", "EXPENSE", "DEBIT", "", 1, "损益类"}
        );
        for (Object[] s : subjects) {
            db.exec("INSERT INTO fin_subject(code,name,category,direction,parent_code,is_leaf,status,remark) "
                            + "VALUES(?,?,?,?,?,?,1,?)",
                    s[0], s[1], s[2], s[3], s[4], s[5], s[6]);
        }
    }

    // =====================================================================
    // 2. 部门与职员
    // =====================================================================
    private void initOrganization() {
        if (count("department") == 0) {
            List<Object[]> deps = List.of(
                    new Object[]{"BM001", "总经办", "周总", "13800000001"},
                    new Object[]{"BM002", "采购部", "陈经理", "13800000002"},
                    new Object[]{"BM003", "销售部", "王经理", "13800000003"},
                    new Object[]{"BM004", "仓储部", "刘主管", "13800000004"},
                    new Object[]{"BM005", "财务部", "赵会计", "13800000005"},
                    new Object[]{"BM006", "生产部", "孙厂长", "13800000006"}
            );
            for (Object[] d : deps) {
                db.exec("INSERT INTO department(code,name,manager,phone,status) VALUES(?,?,?,?,1)",
                        d[0], d[1], d[2], d[3]);
            }
        }
        if (count("employee") == 0) {
            List<Object[]> emps = List.of(
                    new Object[]{"YG001", "张伟", "采购部", "采购主管", "13900000001"},
                    new Object[]{"YG002", "李娜", "销售部", "销售代表", "13900000002"},
                    new Object[]{"YG003", "刘强", "仓储部", "仓库管理员", "13900000003"},
                    new Object[]{"YG004", "赵敏", "财务部", "总账会计", "13900000004"},
                    new Object[]{"YG005", "孙浩", "生产部", "生产计划员", "13900000005"},
                    new Object[]{"YG006", "周颖", "总经办", "总经理", "13900000006"}
            );
            for (Object[] e : emps) {
                db.exec("INSERT INTO employee(code,name,department,position,phone,status) VALUES(?,?,?,?,?,1)",
                        e[0], e[1], e[2], e[3], e[4]);
            }
        }
    }

    // =====================================================================
    // 3. 会计期间：本月前后各一个月，全部 OPEN
    // =====================================================================
    private void initPeriods() {
        LocalDate now = LocalDate.now().withDayOfMonth(1);
        for (int i = -1; i <= 1; i++) {
            String period = now.plusMonths(i).format(DateTimeFormatter.ofPattern("yyyy-MM"));
            Integer c = db.value("SELECT COUNT(*) FROM fin_period WHERE account_period=?", Integer.class, period);
            if (c == null || c == 0) {
                db.exec("INSERT INTO fin_period(account_period,status) VALUES(?,'OPEN')", period);
            }
        }
    }

    // =====================================================================
    // 4. 生产演示数据：先补两种原材料物料，再给「人体工学办公椅」建单层 BOM
    // =====================================================================
    private void initBomAndMaterials() {
        if (count("mf_bom") > 0) {
            return;
        }
        // 物料编码若已存在则复用，保证重复启动不产生重复档案
        long m1 = ensureMaterial("YL001", "椅面网布组件", "套", 90, 100);
        long m2 = ensureMaterial("YL002", "五爪底座组件", "套", 110, 100);
        Map<String, Object> chair = db.one("SELECT id FROM product WHERE code='SP006'");
        if (chair == null) {
            return; // 用户删掉了演示商品，就不强行造 BOM
        }
        long chairId = Val.longOf(chair.get("id"));
        long bomId = db.insert("INSERT INTO mf_bom(bom_code,product_id,version,name,status) "
                + "VALUES('BOM-0001',?,'V1.0','办公椅标准 BOM',1)", chairId);
        // 生产 1 把椅子 = 1 套网布组件（2% 损耗）+ 1 套五爪底座
        db.exec("INSERT INTO mf_bom_item(bom_id,material_id,quantity,loss_rate) VALUES(?,?,?,?)",
                bomId, m1, 1, new BigDecimal("2.00"));
        db.exec("INSERT INTO mf_bom_item(bom_id,material_id,quantity,loss_rate) VALUES(?,?,?,?)",
                bomId, m2, 1, BigDecimal.ZERO);
    }

    private long ensureMaterial(String code, String name, String unit, double cost, int stock) {
        Map<String, Object> row = db.one("SELECT id FROM product WHERE code=?", code);
        if (row != null) {
            return Val.longOf(row.get("id"));
        }
        long id = db.insert("INSERT INTO product(code,name,category,spec,unit,cost_price,sale_price,safety_stock,status) "
                        + "VALUES(?,?,'原材料','生产用物料',?,?,?,20,1)",
                code, name, unit, cost, cost);
        Map<String, Object> wh = db.one("SELECT id FROM warehouse WHERE code='WH001'");
        if (wh != null) {
            db.exec("INSERT INTO inventory(warehouse_id,product_id,quantity) VALUES(?,?,?)",
                    Val.longOf(wh.get("id")), id, stock);
        }
        return id;
    }

    // =====================================================================
    // 5. 给历史演示单据补凭证与往来单
    // =====================================================================
    /**
     * 演示库里本来就有若干已入库 / 已发货的历史单据，它们是在财务模块存在之前生成的。
     * 这里按单据日期逐张补记凭证，使报表一打开就有真实数据。
     * 每张都会经过 BizPostingService 的幂等检查，重复启动不会重复入账。
     */
    private void backfillHistoryVouchers() {
        for (Map<String, Object> o : db.list(
                "SELECT o.order_no, o.total_amount, o.created_at, o.supplier_id, s.name AS partner_name "
                        + "FROM purchase_order o LEFT JOIN supplier s ON s.id=o.supplier_id "
                        + "WHERE o.status='RECEIVED' ORDER BY o.id")) {
            String no = Val.strOf(o.get("order_no"));
            Integer exists = db.value("SELECT COUNT(*) FROM fin_voucher WHERE source_type='PURCHASE_IN' AND biz_no=?",
                    Integer.class, no);
            if (exists != null && exists > 0) {
                continue;
            }
            posting.purchaseIn(no, Val.longOf(o.get("supplier_id")), Val.strOf(o.get("partner_name")),
                    Val.decOf(o.get("total_amount")), dateOf(o.get("created_at")));
        }
        for (Map<String, Object> o : db.list(
                "SELECT o.order_no, o.total_amount, o.created_at, o.customer_id, c.name AS partner_name "
                        + "FROM sales_order o LEFT JOIN customer c ON c.id=o.customer_id "
                        + "WHERE o.status='SHIPPED' ORDER BY o.id")) {
            String no = Val.strOf(o.get("order_no"));
            Integer exists = db.value("SELECT COUNT(*) FROM fin_voucher WHERE source_type='SALE_OUT' AND biz_no=?",
                    Integer.class, no);
            if (exists != null && exists > 0) {
                continue;
            }
            posting.salesOut(no, Val.longOf(o.get("customer_id")), Val.strOf(o.get("partner_name")),
                    Val.decOf(o.get("total_amount")), posting.salesCost(no), dateOf(o.get("created_at")));
        }
    }

    // =====================================================================
    // 6. 期初建账凭证（倒挤法，保证存货科目与实物库存一致且报表平衡）
    // =====================================================================
    /**
     * 期初凭证金额 = 当前实物库存成本 − 已生成凭证中「库存商品」科目的净发生额。
     * 这样资产负债表的存货余额恰好等于实物库存，且借方合计 = 贷方合计恒成立。
     * 真实金蝶是在账套启用时由财务手工录入期初余额，这里用倒挤法自动生成演示数据。
     */
    private void initOpeningVoucher() {
        Integer exists = db.value("SELECT COUNT(*) FROM fin_voucher WHERE source_type='OPENING'", Integer.class);
        if (exists != null && exists > 0) {
            return;
        }
        BigDecimal stockValue = db.value(
                "SELECT COALESCE(SUM(i.quantity * p.cost_price),0) FROM inventory i "
                        + "JOIN product p ON p.id=i.product_id", BigDecimal.class);
        BigDecimal netGoods = db.value(
                "SELECT COALESCE(SUM(e.debit - e.credit),0) FROM fin_voucher_entry e "
                        + "JOIN fin_voucher v ON v.id=e.voucher_id "
                        + "WHERE v.status='POSTED' AND e.subject_code='1405'", BigDecimal.class);
        BigDecimal opening = (stockValue == null ? BigDecimal.ZERO : stockValue)
                .subtract(netGoods == null ? BigDecimal.ZERO : netGoods);
        if (opening.signum() > 0) {
            posting.opening(opening, LocalDate.now().withDayOfMonth(1));
        }
    }

    // =====================================================================
    // 工具
    // =====================================================================
    private int count(String table) {
        Integer c = db.value("SELECT COUNT(*) FROM " + table, Integer.class);
        return c == null ? 0 : c;
    }

    private static LocalDate dateOf(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        try {
            return LocalDate.parse(String.valueOf(value).substring(0, 10));
        } catch (Exception e) {
            return LocalDate.now();
        }
    }
}
