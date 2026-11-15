package com.erp.common;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 单据编号生成器：PO20261109-0001 / SO20261109-0001 / PZ20261109-0001 ...
 *
 * <p>借鉴金蝶的「单据编码规则」：前缀标识业务类型、日期便于归档、四位流水号保证当日唯一。
 * 实现方式是查询当日已有最大编号再 +1（而不是单独维护序列表），
 * 在单实例部署下足够用；若将来做集群部署，应改为数据库序列或 Redis 原子自增，
 * 否则并发下单可能撞号（届时靠唯一索引兜底会直接报错）。
 */
@Component
public class NoGenerator {

    private final Db db;

    public NoGenerator(Db db) {
        this.db = db;
    }

    public String purchase() {
        return next("PO", "purchase_order");
    }

    public String sales() {
        return next("SO", "sales_order");
    }

    public String flow() {
        return "FL" + System.currentTimeMillis() + String.format("%03d", (int) (Math.random() * 1000));
    }

    // -----------------------------------------------------------------------
    // 以下为扩展模块单号。金蝶把这套规则称为「单据编码规则 / 单据自定义」：
    // 前缀 + 日期 + 当日流水号，既便于人工识别业务类型，也便于按日期归档。
    // 所有方法都复用 next(...)，只是表名与字段不同，因此抽成 nextBy(...)。
    // -----------------------------------------------------------------------

    /** 记账凭证号：PZ20261110-0001 */
    public String voucher() {
        return nextBy("PZ", "fin_voucher", "voucher_no");
    }

    /** 应收单号：AR20261110-0001 */
    public String arBill() {
        return nextBy("AR", "fin_ar_ap", "bill_no");
    }

    /** 应付单号：AP20261110-0001 */
    public String apBill() {
        return nextBy("AP", "fin_ar_ap", "bill_no");
    }

    /** 收付款单号：SK 收款 / FK 付款 */
    public String settlement(boolean receive) {
        return nextBy(receive ? "SK" : "FK", "fin_settlement", "settle_no");
    }

    /** 生产任务单号：MO20261110-0001 */
    public String manufacture() {
        return nextBy("MO", "mf_order", "order_no");
    }

    /** BOM 编码：BOM-0001（BOM 不按日期编号，按版本管理） */
    public String bom() {
        String max = db.value("SELECT MAX(bom_code) FROM mf_bom WHERE bom_code LIKE 'BOM-%'", String.class);
        int seq = 1;
        if (max != null && max.length() > 4) {
            try {
                seq = Integer.parseInt(max.substring(4)) + 1;
            } catch (NumberFormatException ignored) {
                // 兼容手工录入的不规则编码
            }
        }
        return "BOM-" + String.format("%04d", seq);
    }

    /** 通用「按日期 + 当日最大流水」生成器；table / column 均为代码内常量，不接受外部输入，杜绝注入 */
    private String nextBy(String prefix, String table, String column) {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String max = db.value("SELECT MAX(" + column + ") FROM " + table + " WHERE " + column + " LIKE ?",
                String.class, prefix + day + "-%");
        int seq = 1;
        if (max != null && max.length() >= 4) {
            try {
                seq = Integer.parseInt(max.substring(max.length() - 4)) + 1;
            } catch (NumberFormatException ignored) {
                // 兼容历史不规则单号，从 1 重新开始
            }
        }
        return prefix + day + "-" + String.format("%04d", seq);
    }

    /** 采购 / 销售单沿用历史字段名 order_no */
    private String next(String prefix, String table) {
        return nextBy(prefix, table, "order_no");
    }
}
