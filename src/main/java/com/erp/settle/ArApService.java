package com.erp.settle;

import com.erp.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 应收应付与收付款核销 —— 对应金蝶 K/3「应收款管理 / 应付款管理」。
 *
 * <h3>业务闭环</h3>
 * <pre>
 *   销售出库 → 生成应收单 AR（未结清）→ 客户付款 → 收款单核销 → 应收单结清
 *   采购入库 → 生成应付单 AP（未结清）→ 向供应商付款 → 付款单核销 → 应付单结清
 * </pre>
 * 核销（金蝶叫「往来核销」）的金科玉律：一笔收款可以部分核销，
 * 但累计核销金额绝不能超过应收金额。本类用 BigDecimal 精确比较，
 * 并在同一事务里完成「写收款单 + 更新往来单 + 生成会计凭证」，
 * 任一步失败全部回滚，不会出现「钱收了、账没销」。
 *
 * <h3>状态</h3>
 * OPEN 未核销 / PARTIAL 部分核销 / CLOSED 已结清。
 */
@Service
public class ArApService {

    private final Db db;
    private final NoGenerator noGenerator;
    private final BizPostingService posting;
    private final LogService logService;

    public ArApService(Db db, NoGenerator noGenerator, BizPostingService posting, LogService logService) {
        this.db = db;
        this.noGenerator = noGenerator;
        this.posting = posting;
        this.logService = logService;
    }

    /** 往来单列表，附带「未核销金额」与「账龄天数」两个计算字段 */
    public List<Map<String, Object>> list(String type, String status, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT * FROM fin_ar_ap WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            sql.append(" AND bill_type=?");
            args.add(type);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (bill_no LIKE ? OR partner_name LIKE ? OR biz_no LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            Collections.addAll(args, kw, kw, kw);
        }
        sql.append(" ORDER BY id DESC LIMIT 500");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : db.list(sql.toString(), args.toArray())) {
            Map<String, Object> row = normalize(raw);
            BigDecimal amount = Val.decOf(row.get("amount"));
            BigDecimal settled = Val.decOf(row.get("settled_amount"));
            row.put("open_amount", amount.subtract(settled));
            row.put("age_days", ageDays(row.get("bill_date")));
            row.put("overdue", isOverdue(row.get("due_date"), settled.compareTo(amount) < 0));
            rows.add(row);
        }
        return rows;
    }

    /** 往来汇总：应收/应付总额、已核销、未核销、逾期笔数 —— 供页面顶部卡片展示 */
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String type : List.of("AR", "AP")) {
            Map<String, Object> r = db.one(
                    "SELECT COALESCE(SUM(amount),0) AS total, COALESCE(SUM(settled_amount),0) AS settled, "
                            + "COUNT(*) AS bills FROM fin_ar_ap WHERE bill_type=?", type);
            BigDecimal total = r == null ? BigDecimal.ZERO : Val.decOf(r.get("total"));
            BigDecimal settled = r == null ? BigDecimal.ZERO : Val.decOf(r.get("settled"));
            String prefix = "AR".equals(type) ? "ar" : "ap";
            result.put(prefix + "Total", total);
            result.put(prefix + "Settled", settled);
            result.put(prefix + "Open", total.subtract(settled));
            result.put(prefix + "Bills", r == null ? 0 : Val.intOf(r.get("bills")));
        }
        long overdue = 0;
        for (Map<String, Object> r : db.list(
                "SELECT due_date FROM fin_ar_ap WHERE settled_amount < amount")) {
            if (isOverdue(r.get("due_date"), true)) {
                overdue++;
            }
        }
        result.put("overdueBills", overdue);
        return result;
    }

    /**
     * 账龄分析：把未结清的往来单按天数分桶（0-30 / 31-60 / 61-90 / 90 天以上）。
     * 金蝶的账龄分析表也是这个思路，用来评估坏账风险与催款优先级。
     */
    public List<Map<String, Object>> aging(String type) {
        List<Map<String, Object>> buckets = new ArrayList<>();
        String[] labels = {"0-30 天", "31-60 天", "61-90 天", "90 天以上"};
        BigDecimal[] amounts = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        int[] counts = new int[4];

        StringBuilder sql = new StringBuilder(
                "SELECT bill_date, amount, settled_amount FROM fin_ar_ap WHERE settled_amount < amount");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            sql.append(" AND bill_type=?");
            args.add(type);
        }
        for (Map<String, Object> r : db.list(sql.toString(), args.toArray())) {
            int age = ageDays(r.get("bill_date"));
            int idx = age <= 30 ? 0 : age <= 60 ? 1 : age <= 90 ? 2 : 3;
            amounts[idx] = amounts[idx].add(Val.decOf(r.get("amount"))
                    .subtract(Val.decOf(r.get("settled_amount"))));
            counts[idx]++;
        }
        for (int i = 0; i < 4; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", labels[i]);
            m.put("amount", amounts[i]);
            m.put("bills", counts[i]);
            buckets.add(m);
        }
        return buckets;
    }

    /** 收付款（核销）记录 */
    public List<Map<String, Object>> records(String keyword) {
        StringBuilder sql = new StringBuilder("SELECT * FROM fin_settlement WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (settle_no LIKE ? OR partner_name LIKE ? OR bill_no LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            Collections.addAll(args, kw, kw, kw);
        }
        sql.append(" ORDER BY id DESC LIMIT 500");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : db.list(sql.toString(), args.toArray())) {
            rows.add(normalize(r));
        }
        return rows;
    }

    /**
     * 执行收款 / 付款核销。
     * 前端传参：{"ar_ap_id": 12, "amount": 5000, "pay_method": "BANK", "settle_date": "2026-11-10"}
     *
     * <p>事务内四步：校验 → 写核销记录 → 更新往来单 → 生成会计凭证。
     * 若最后生成凭证失败（例如期间已结账），前三步一并回滚，数据保持一致。
     */
    @Transactional
    public long settle(Map<String, Object> body) {
        long arApId = Db.lng(body, "ar_ap_id", 0);
        BigDecimal amount = Db.decimal(body, "amount");
        Map<String, Object> bill = db.one("SELECT * FROM fin_ar_ap WHERE id=?", arApId);
        if (bill == null) {
            throw new BizException("往来单据不存在");
        }
        if (amount.signum() <= 0) {
            throw new BizException("核销金额必须大于 0");
        }
        BigDecimal total = Val.decOf(bill.get("amount"));
        BigDecimal settled = Val.decOf(bill.get("settled_amount"));
        BigDecimal open = total.subtract(settled);
        if ("CLOSED".equals(Val.strOf(bill.get("status"))) || open.signum() <= 0) {
            throw new BizException("该往来单已结清，无需再核销");
        }
        if (amount.compareTo(open) > 0) {
            throw new BizException("核销金额不能大于未结金额 " + open);
        }

        String type = Val.strOf(bill.get("bill_type"));
        boolean receive = "AR".equals(type);
        String settleNo = noGenerator.settlement(receive);
        String partnerName = Val.strOf(bill.get("partner_name"));
        LocalDate date = parseDate(Db.str(body, "settle_date"));

        long id = db.insert("INSERT INTO fin_settlement(settle_no,settle_type,ar_ap_id,bill_no,partner_name,"
                        + "amount,pay_method,settle_date,remark,created_by,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                settleNo, receive ? "RECEIVE" : "PAY", arApId, Val.strOf(bill.get("bill_no")),
                partnerName, amount, defaultString(Db.str(body, "pay_method"), "BANK"),
                java.sql.Date.valueOf(date), Db.str(body, "remark"),
                UserContext.get() == null ? "admin" : UserContext.get());

        BigDecimal newSettled = settled.add(amount);
        String newStatus = newSettled.compareTo(total) >= 0 ? "CLOSED" : "PARTIAL";
        db.exec("UPDATE fin_ar_ap SET settled_amount=?, status=? WHERE id=?", newSettled, newStatus, arApId);

        // 业财一体化：核销同步生成凭证（收款借银行 / 付款贷银行）
        posting.settlement(receive, settleNo, partnerName, amount, date);
        logService.record(receive ? "收款核销" : "付款核销",
                settleNo + " / " + partnerName + " / " + amount + " / " + newStatus);
        return id;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private static int ageDays(Object billDate) {
        if (billDate == null) {
            return 0;
        }
        LocalDate d = billDate instanceof java.sql.Date sd ? sd.toLocalDate()
                : LocalDate.parse(String.valueOf(billDate).substring(0, 10));
        long days = java.time.temporal.ChronoUnit.DAYS.between(d, LocalDate.now());
        return (int) Math.max(0, days);
    }

    private static boolean isOverdue(Object dueDate, boolean unsettled) {
        if (!unsettled || dueDate == null) {
            return false;
        }
        LocalDate d = dueDate instanceof java.sql.Date sd ? sd.toLocalDate()
                : LocalDate.parse(String.valueOf(dueDate).substring(0, 10));
        return d.isBefore(LocalDate.now());
    }

    private static LocalDate parseDate(String text) {
        if (text.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(text.substring(0, Math.min(10, text.length())));
        } catch (Exception e) {
            throw new BizException("日期格式不正确，应为 yyyy-MM-dd");
        }
    }

    private static String defaultString(String v, String fallback) {
        return v.isEmpty() ? fallback : v;
    }

    /** DATE 类型统一转 yyyy-MM-dd 字符串，屏蔽 H2 / MySQL 驱动差异 */
    private static Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        for (Map.Entry<String, Object> e : copy.entrySet()) {
            Object v = e.getValue();
            if (v instanceof java.sql.Date d) {
                e.setValue(d.toLocalDate().toString());
            } else if (v instanceof LocalDate d) {
                e.setValue(d.toString());
            }
        }
        return copy;
    }
}
