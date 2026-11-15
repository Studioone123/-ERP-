package com.erp.common;

import com.erp.finance.VoucherService;
import com.erp.finance.VoucherService.VoucherLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 业财一体化凭证模板 —— 借鉴金蝶 K/3「业务系统 → 凭证模板 → 总账」的核心机制。
 *
 * <h3>为什么要单独抽一层</h3>
 * 普通进销存软件里，采购入库只是「库存 +N」；在 ERP 里，同一动作必须同时
 * 产生三条财务痕迹：
 * <ol>
 *   <li>会计凭证（借 库存商品 / 贷 应付账款）</li>
 *   <li>应付单（这笔钱以后要付给谁、付多少）</li>
 *   <li>库存流水（由 {@link com.erp.inventory.StockService} 负责）</li>
 * </ol>
 * 如果把这套逻辑写进 PurchaseService，销售、生产、收付款都要各写一遍，
 * 科目一变就要改五处。金蝶的做法是定义「凭证模板」：业务只抛出一个事件，
 * 模板决定借贷科目。本类就是这个模板层。
 *
 * <h3>模板清单（科目对照表）</h3>
 * <pre>
 *  业务事件          借方              贷方              同时生成
 *  ------------------------------------------------------------------
 *  采购入库          1405 库存商品     2202 应付账款      应付单 AP
 *  销售出库-收入     1122 应收账款     6001 主营业务收入  应收单 AR
 *  销售出库-成本     6401 主营业务成本 1405 库存商品      （同上，第二张凭证）
 *  收款核销          1002 银行存款     1122 应收账款      收款单 SK
 *  付款核销          2202 应付账款     1002 银行存款      付款单 FK
 *  生产领料          5001 生产成本     1405 库存商品      —
 *  完工入库          1405 库存商品     5001 生产成本      —
 *  期初建账          1405 库存商品     4001 实收资本      —
 * </pre>
 *
 * <h3>幂等性</h3>
 * 初始化器会给「历史演示单据」补凭证，业务代码也会给新单据生成凭证，
 * 因此每个方法都先按 (source_type, biz_no) 查重，避免重复入账。
 * 真实金蝶靠「业务单据是否已生成凭证」标志位控制，这里用查重达到同样效果。
 */
@Service
public class BizPostingService {

    // -----------------------------------------------------------------------
    // 科目编码常量：相当于金蝶「科目对照表」的配置项。
    // 抽成常量后，财务要换科目（例如明细到 140501）只改这一处。
    // -----------------------------------------------------------------------
    public static final String SUB_CASH = "1001";        // 库存现金
    public static final String SUB_BANK = "1002";        // 银行存款
    public static final String SUB_AR = "1122";          // 应收账款
    public static final String SUB_RAW = "1403";         // 原材料
    public static final String SUB_GOODS = "1405";       // 库存商品
    public static final String SUB_AP = "2202";          // 应付账款
    public static final String SUB_CAPITAL = "4001";     // 实收资本
    public static final String SUB_PROFIT = "4103";      // 本年利润
    public static final String SUB_PRODUCTION = "5001";  // 生产成本
    public static final String SUB_REVENUE = "6001";     // 主营业务收入
    public static final String SUB_COGS = "6401";        // 主营业务成本
    public static final String SUB_SELLING_EXP = "6601"; // 销售费用
    public static final String SUB_ADMIN_EXP = "6602";   // 管理费用

    private final Db db;
    private final VoucherService voucherService;
    private final NoGenerator noGenerator;

    public BizPostingService(Db db, VoucherService voucherService, NoGenerator noGenerator) {
        this.db = db;
        this.voucherService = voucherService;
        this.noGenerator = noGenerator;
    }

    // =====================================================================
    // 采购 / 销售：业务单据执行时调用
    // =====================================================================

    /**
     * 采购收货入库的财务处理：
     * <pre>
     *   借：1405 库存商品   amount     （货到了，资产增加）
     *   贷：2202 应付账款   amount     （钱未付，负债增加）
     * </pre>
     * 同时生成一张应付单，供后续付款核销。
     */
    @Transactional
    public void purchaseIn(String orderNo, long supplierId, String supplierName,
                           BigDecimal amount, LocalDate date) {
        if (amount == null || amount.signum() <= 0) {
            return; // 零金额单据不产生凭证，避免账簿里出现无意义记录
        }
        if (voucherExists("PURCHASE_IN", orderNo)) {
            return; // 幂等：已为该单据生成过凭证
        }
        voucherService.postGenerated("PURCHASE_IN", orderNo, date, "转",
                "采购入库 " + orderNo + " / " + supplierName,
                List.of(VoucherLine.debit(SUB_GOODS, "采购入库 " + orderNo, amount),
                        VoucherLine.credit(SUB_AP, "应付 " + supplierName, amount)),
                "POSTED");
        createArAp("AP", "SUPPLIER", supplierId, supplierName, orderNo, amount, date);
    }

    /**
     * 销售发货出库的财务处理，需要两张凭证（金蝶称为「收入确认 + 成本结转」）：
     * <pre>
     *   凭证一  借：1122 应收账款   saleAmount     （客户欠款）
     *           贷：6001 主营业务收入 saleAmount   （收入实现）
     *   凭证二  借：6401 主营业务成本 costAmount   （成本发生）
     *           贷：1405 库存商品   costAmount     （存货减少）
     * </pre>
     * 两张凭证分开，是为了让利润表能直接取「收入」与「成本」两个科目的发生额，
     * 也符合会计上「收入与成本配比」原则。
     */
    @Transactional
    public void salesOut(String orderNo, long customerId, String customerName,
                         BigDecimal saleAmount, BigDecimal costAmount, LocalDate date) {
        if (saleAmount == null || saleAmount.signum() <= 0) {
            return;
        }
        if (!voucherExists("SALE_OUT", orderNo)) {
            voucherService.postGenerated("SALE_OUT", orderNo, date, "转",
                    "销售出库 " + orderNo + " / " + customerName,
                    List.of(VoucherLine.debit(SUB_AR, "应收 " + customerName, saleAmount),
                            VoucherLine.credit(SUB_REVENUE, "销售收入 " + orderNo, saleAmount)),
                    "POSTED");
        }
        BigDecimal cost = costAmount == null ? BigDecimal.ZERO : costAmount;
        if (cost.signum() > 0 && !voucherExists("SALE_COST", orderNo)) {
            voucherService.postGenerated("SALE_COST", orderNo, date, "转",
                    "结转销售成本 " + orderNo,
                    List.of(VoucherLine.debit(SUB_COGS, "结转销售成本 " + orderNo, cost),
                            VoucherLine.credit(SUB_GOODS, "出库成本 " + orderNo, cost)),
                    "POSTED");
        }
        // 应收单按含税/不含税口径这里简化为订单总额，与凭证一保持一致
        if (!arApExists("AR", orderNo)) {
            createArAp("AR", "CUSTOMER", customerId, customerName, orderNo, saleAmount, date);
        }
    }

    /**
     * 根据销售单号计算「结转成本」金额 = Σ(销售数量 × 商品成本价)。
     * 成本价取商品档案的 cost_price（移动加权 / 全月平均属于进阶核算，见导读文档）。
     */
    public BigDecimal salesCost(String orderNo) {
        BigDecimal cost = db.value(
                "SELECT COALESCE(SUM(i.quantity * p.cost_price),0) "
                        + "FROM sales_item i JOIN product p ON p.id=i.product_id "
                        + "JOIN sales_order o ON o.id=i.order_id WHERE o.order_no=?",
                BigDecimal.class, orderNo);
        return cost == null ? BigDecimal.ZERO : cost;
    }

    // =====================================================================
    // 收付款核销
    // =====================================================================

    /**
     * 收款：借 银行存款 / 贷 应收账款；付款：借 应付账款 / 贷 银行存款。
     * 这样「应收账款」科目余额随收款减少，往来单状态随之结清。
     */
    @Transactional
    public void settlement(boolean receive, String settleNo, String partnerName,
                           BigDecimal amount, LocalDate date) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        List<VoucherLine> lines = receive
                ? List.of(VoucherLine.debit(SUB_BANK, "收到 " + partnerName + " 货款", amount),
                        VoucherLine.credit(SUB_AR, "冲减应收 " + partnerName, amount))
                : List.of(VoucherLine.debit(SUB_AP, "支付 " + partnerName + " 货款", amount),
                        VoucherLine.credit(SUB_BANK, "支付货款 " + partnerName, amount));
        voucherService.postGenerated("SETTLE", settleNo, date, receive ? "收" : "付",
                (receive ? "收款 " : "付款 ") + partnerName + " / " + settleNo,
                lines, "POSTED");
    }

    // =====================================================================
    // 生产制造
    // =====================================================================

    /**
     * 生产领料：物料从库存转入生产成本（尚未完工，属于在制品）。
     * 本系统所有物料档案统一记在 1405 库存商品，因此贷方也用 1405，
     * 与采购入库 / 销售成本结转保持同一科目，账簿自洽；
     * 若启用 1403 原材料科目，需同时把期初建账拆成「原材料 + 库存商品」两行。
     */
    @Transactional
    public void materialIssue(String orderNo, BigDecimal amount, LocalDate date) {
        if (amount == null || amount.signum() <= 0 || voucherExists("MATERIAL_ISSUE", orderNo)) {
            return;
        }
        voucherService.postGenerated("MATERIAL_ISSUE", orderNo, date, "转",
                "生产领料 " + orderNo,
                List.of(VoucherLine.debit(SUB_PRODUCTION, "领料投产 " + orderNo, amount),
                        VoucherLine.credit(SUB_GOODS, "生产领料出库 " + orderNo, amount)),
                "POSTED");
    }

    /** 完工入库：在制品转为产成品，库存商品增加、生产成本结平 */
    @Transactional
    public void productFinish(String orderNo, BigDecimal amount, LocalDate date) {
        if (amount == null || amount.signum() <= 0 || voucherExists("PRODUCT_FINISH", orderNo)) {
            return;
        }
        voucherService.postGenerated("PRODUCT_FINISH", orderNo, date, "转",
                "完工入库 " + orderNo,
                List.of(VoucherLine.debit(SUB_GOODS, "产成品入库 " + orderNo, amount),
                        VoucherLine.credit(SUB_PRODUCTION, "结转生产成本 " + orderNo, amount)),
                "POSTED");
    }

    // =====================================================================
    // 期初建账
    // =====================================================================

    /**
     * 期初建账凭证：把系统启用时已有的库存折算成「实收资本」入账。
     * 没有这一步，资产负债表会出现「有资产、无权益」的不平衡，
     * 这也是金蝶账套启用时必须录「期初余额」的原因。
     */
    @Transactional
    public void opening(BigDecimal stockValue, LocalDate date) {
        if (stockValue == null || stockValue.signum() <= 0 || voucherExists("OPENING", "INIT")) {
            return;
        }
        voucherService.postGenerated("OPENING", "INIT", date, "转", "期初建账 · 库存商品初始余额",
                List.of(VoucherLine.debit(SUB_GOODS, "期初库存", stockValue),
                        VoucherLine.credit(SUB_CAPITAL, "期初投入资本", stockValue)),
                "POSTED");
    }

    // =====================================================================
    // 往来单（应收 / 应付）
    // =====================================================================

    /**
     * 生成往来单。金蝶里应收单由销售发票/出库单下推生成，
     * 这里由出库/入库事件直接生成，字段保持一致：金额、已核销、状态、到期日。
     * 默认账期 30 天，用于账龄分析。
     */
    private void createArAp(String type, String partnerType, long partnerId, String partnerName,
                            String bizNo, BigDecimal amount, LocalDate date) {
        db.insert("INSERT INTO fin_ar_ap(bill_no,bill_type,partner_type,partner_id,partner_name,biz_no,"
                        + "amount,settled_amount,status,bill_date,due_date) VALUES(?,?,?,?,?,?,?,0,'OPEN',?,?)",
                "AR".equals(type) ? noGenerator.arBill() : noGenerator.apBill(),
                type, partnerType, partnerId, partnerName, bizNo, amount,
                java.sql.Date.valueOf(date), java.sql.Date.valueOf(date.plusDays(30)));
    }

    private boolean arApExists(String type, String bizNo) {
        Integer c = db.value("SELECT COUNT(*) FROM fin_ar_ap WHERE bill_type=? AND biz_no=?",
                Integer.class, type, bizNo);
        return c != null && c > 0;
    }

    private boolean voucherExists(String sourceType, String bizNo) {
        Integer c = db.value("SELECT COUNT(*) FROM fin_voucher WHERE source_type=? AND biz_no=?",
                Integer.class, sourceType, bizNo);
        return c != null && c > 0;
    }
}
