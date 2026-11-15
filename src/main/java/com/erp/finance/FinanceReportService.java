package com.erp.finance;

import com.erp.common.BizException;
import com.erp.common.Db;
import com.erp.common.LogService;
import com.erp.common.UserContext;
import com.erp.common.Val;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 账簿与财务报表 —— 对应金蝶 K/3「总账系统 → 账簿查询 / 报表 / 期末结账」。
 *
 * <h3>核心公式</h3>
 * <pre>
 *   借方余额科目（资产 / 成本 / 费用）：期末余额 = 期初 + 本期借方 - 本期贷方
 *   贷方余额科目（负债 / 权益 / 收入）：期末余额 = 期初 + 本期贷方 - 本期借方
 *
 *   利润表：利润总额 = 收入 - 成本 - 费用
 *   资产负债表：资产 = 负债 + 所有者权益
 * </pre>
 * 资产负债表的「所有者权益」里包含本年利润（损益类科目累计净额），
 * 这正是利润表与资产负债表之间的勾稽关系（金蝶叫「报表取数公式」）。
 *
 * <h3>数据来源</h3>
 * 只统计 status='POSTED' 的凭证分录。草稿凭证、作废凭证一律不进账簿，
 * 保证「看到的报表」与「已审核的账」完全一致。
 */
@Service
public class FinanceReportService {

    private final Db db;
    private final LogService logService;

    public FinanceReportService(Db db, LogService logService) {
        this.db = db;
        this.logService = logService;
    }

    // =====================================================================
    // 会计期间与期末结账
    // =====================================================================

    /** 期间列表，附带每个期间的凭证张数，便于结账前检查 */
    public List<Map<String, Object>> periods() {
        return db.list("SELECT p.account_period, p.status, p.closed_by, p.closed_at, "
                + "(SELECT COUNT(*) FROM fin_voucher v WHERE v.account_period=p.account_period) AS voucher_count, "
                + "(SELECT COUNT(*) FROM fin_voucher v WHERE v.account_period=p.account_period AND v.status='DRAFT') AS draft_count "
                + "FROM fin_period p ORDER BY p.account_period DESC");
    }

    /**
     * 期末结账 / 反结账。
     * 结账前置条件（金蝶同样会卡）：本期间不能存在草稿凭证。
     * 结账后 {@link VoucherService} 会拒绝该期间新增或修改凭证。
     */
    @Transactional
    public void closePeriod(String period, boolean reopen) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw new BizException("会计期间格式应为 yyyy-MM");
        }
        Integer c = db.value("SELECT COUNT(*) FROM fin_period WHERE account_period=?", Integer.class, period);
        if (c == null || c == 0) {
            db.exec("INSERT INTO fin_period(account_period,status) VALUES(?,'OPEN')", period);
        }
        if (!reopen) {
            Integer drafts = db.value("SELECT COUNT(*) FROM fin_voucher "
                    + "WHERE account_period=? AND status='DRAFT'", Integer.class, period);
            if (drafts != null && drafts > 0) {
                throw new BizException("本期还有 " + drafts + " 张草稿凭证未过账，不能结账");
            }
        }
        db.exec("UPDATE fin_period SET status=?, closed_by=?, closed_at=? WHERE account_period=?",
                reopen ? "OPEN" : "CLOSED",
                UserContext.get() == null ? "admin" : UserContext.get(),
                new java.sql.Timestamp(System.currentTimeMillis()), period);
        logService.record(reopen ? "反结账" : "期末结账", "会计期间 " + period);
    }

    // =====================================================================
    // 科目余额表（金蝶「科目余额表」）
    // =====================================================================

    /**
     * 按会计期间输出全部科目的：期初余额、本期借方、本期贷方、期末余额。
     * 采用「一次性查三段汇总 + Java 内存归并」，而不是逐科目查库，
     * 避免 N+1 查询；科目数量通常只有几十个，内存归并开销可忽略。
     */
    public List<Map<String, Object>> subjectBalance(String period) {
        String p = blankToCurrentPeriod(period);
        Map<String, BigDecimal[]> opening = sums(" AND v.account_period < ?", p);   // 期初累计
        Map<String, BigDecimal[]> current = sums(" AND v.account_period = ?", p);   // 本期发生
        Map<String, BigDecimal[]> cumulative = sums(" AND v.account_period <= ?", p); // 期末累计

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> s : db.list("SELECT * FROM fin_subject WHERE status=1 ORDER BY code")) {
            String code = Val.strOf(s.get("code"));
            boolean debitSide = "DEBIT".equals(Val.strOf(s.get("direction")));
            BigDecimal[] open = opening.getOrDefault(code, ZERO2);
            BigDecimal[] curr = current.getOrDefault(code, ZERO2);
            BigDecimal[] total = cumulative.getOrDefault(code, ZERO2);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("name", Val.strOf(s.get("name")));
            row.put("category", Val.strOf(s.get("category")));
            row.put("direction", Val.strOf(s.get("direction")));
            row.put("opening_balance", balance(open[0], open[1], debitSide));
            row.put("period_debit", curr[0]);
            row.put("period_credit", curr[1]);
            row.put("closing_balance", balance(total[0], total[1], debitSide));
            // 全为 0 的科目也保留，方便财务核对科目体系完整性
            rows.add(row);
        }
        return rows;
    }

    // =====================================================================
    // 明细账（金蝶「明细分类账」）
    // =====================================================================

    /**
     * 某科目在日期区间内的逐笔流水，并滚动计算余额。
     * 期初余额取「区间开始日之前」的全部已过账分录累计。
     */
    public List<Map<String, Object>> detailLedger(String subjectCode, String from, String to) {
        if (subjectCode == null || subjectCode.isBlank()) {
            throw new BizException("请选择会计科目");
        }
        Map<String, Object> subject = db.one("SELECT * FROM fin_subject WHERE code=?", subjectCode);
        if (subject == null) {
            throw new BizException("会计科目不存在");
        }
        boolean debitSide = "DEBIT".equals(Val.strOf(subject.get("direction")));
        LocalDate fromDate = parseDate(from, LocalDate.now().withDayOfMonth(1));
        LocalDate toDate = parseDate(to, LocalDate.now());

        // 期初：日期早于 fromDate 的累计发生额
        BigDecimal[] open = sumOne(
                "SELECT COALESCE(SUM(e.debit),0), COALESCE(SUM(e.credit),0) FROM fin_voucher_entry e "
                        + "JOIN fin_voucher v ON v.id=e.voucher_id "
                        + "WHERE v.status='POSTED' AND e.subject_code=? AND v.voucher_date < ?",
                subjectCode, java.sql.Date.valueOf(fromDate));
        BigDecimal running = balance(open[0], open[1], debitSide);

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> openingRow = new LinkedHashMap<>();
        openingRow.put("voucher_date", fromDate.toString());
        openingRow.put("voucher_no", "—");
        openingRow.put("summary", "期初余额");
        openingRow.put("debit", BigDecimal.ZERO);
        openingRow.put("credit", BigDecimal.ZERO);
        openingRow.put("balance", running);
        rows.add(openingRow);

        List<Map<String, Object>> entries = db.list(
                "SELECT v.voucher_no, v.voucher_date, v.voucher_word, v.summary, "
                        + "e.summary AS entry_summary, e.debit, e.credit, v.source_type, v.biz_no "
                        + "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id=e.voucher_id "
                        + "WHERE v.status='POSTED' AND e.subject_code=? "
                        + "AND v.voucher_date BETWEEN ? AND ? "
                        + "ORDER BY v.voucher_date, v.id, e.line_no",
                subjectCode, java.sql.Date.valueOf(fromDate), java.sql.Date.valueOf(toDate));
        for (Map<String, Object> e : entries) {
            BigDecimal debit = Val.decOf(e.get("debit"));
            BigDecimal credit = Val.decOf(e.get("credit"));
            // 借方科目：借增贷减；贷方科目：贷增借减
            running = debitSide ? running.add(debit).subtract(credit)
                    : running.add(credit).subtract(debit);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("voucher_date", dateText(e.get("voucher_date")));
            row.put("voucher_no", Val.strOf(e.get("voucher_no")));
            row.put("summary", Val.strOf(e.get("entry_summary")).isEmpty()
                    ? Val.strOf(e.get("summary")) : Val.strOf(e.get("entry_summary")));
            row.put("debit", debit);
            row.put("credit", credit);
            row.put("balance", running);
            row.put("source_type", Val.strOf(e.get("source_type")));
            row.put("biz_no", Val.strOf(e.get("biz_no")));
            rows.add(row);
        }
        return rows;
    }

    // =====================================================================
    // 利润表（金蝶「利润表 / 损益表」）
    // =====================================================================

    /**
     * 本期数与本年累计数并列展示。
     * 收入类取「贷方 - 借方」，成本费用类取「借方 - 贷方」，
     * 这样即使发生销售退回（红字冲销）也能正确反映。
     */
    public Map<String, Object> profit(String period) {
        String p = blankToCurrentPeriod(period);
        String year = p.substring(0, 4);

        // 注意：5001 生产成本属于「成本类」但期末余额是在制品（资产），不是损益，
        // 因此利润表只取 6401 主营业务成本，不能用 category=COST 一把抓。
        BigDecimal revenue = netByCategory("INCOME", " AND v.account_period = ?", p);
        BigDecimal cogs = netSubject("6401", " AND v.account_period = ?", p);
        BigDecimal sellingExp = netSubject("6601", " AND v.account_period = ?", p);
        BigDecimal adminExp = netSubject("6602", " AND v.account_period = ?", p);
        BigDecimal periodProfit = revenue.subtract(cogs).subtract(sellingExp).subtract(adminExp);

        // 本年累计：同一年度内、期间小于等于当前的全部已过账凭证
        BigDecimal ytdRevenue = netByCategory("INCOME", " AND v.account_period <= ? AND v.account_period >= ?", p, year + "-01");
        BigDecimal ytdCogs = netSubject("6401", " AND v.account_period <= ? AND v.account_period >= ?", p, year + "-01");
        BigDecimal ytdSelling = netSubject("6601", " AND v.account_period <= ? AND v.account_period >= ?", p, year + "-01");
        BigDecimal ytdAdmin = netSubject("6602", " AND v.account_period <= ? AND v.account_period >= ?", p, year + "-01");
        BigDecimal ytdProfit = ytdRevenue.subtract(ytdCogs).subtract(ytdSelling).subtract(ytdAdmin);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", p);
        result.put("rows", List.of(
                line("一、营业收入", revenue, ytdRevenue),
                line("减：主营业务成本", cogs, ytdCogs),
                line("减：销售费用", sellingExp, ytdSelling),
                line("减：管理费用", adminExp, ytdAdmin),
                line("二、利润总额", periodProfit, ytdProfit)));
        result.put("periodProfit", periodProfit);
        result.put("ytdProfit", ytdProfit);
        return result;
    }

    // =====================================================================
    // 资产负债表（金蝶「资产负债表」简化版）
    // =====================================================================

    /**
     * 资产 = 负债 + 所有者权益（含本年利润）。
     * 「本年利润」来自损益类科目的累计净额，使两张报表自动勾稽；
     * 生产成本（5001）期末余额代表在制品，按会计准则计入存货（资产方）。
     */
    public Map<String, Object> balance(String period) {
        String p = blankToCurrentPeriod(period);
        Map<String, BigDecimal[]> cumulative = sums(" AND v.account_period <= ?", p);

        List<Map<String, Object>> assets = new ArrayList<>();
        List<Map<String, Object>> liabilities = new ArrayList<>();
        List<Map<String, Object>> equities = new ArrayList<>();
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiability = BigDecimal.ZERO;
        BigDecimal totalEquity = BigDecimal.ZERO;

        for (Map<String, Object> s : db.list("SELECT * FROM fin_subject WHERE status=1 ORDER BY code")) {
            String code = Val.strOf(s.get("code"));
            String category = Val.strOf(s.get("category"));
            String direction = Val.strOf(s.get("direction"));
            BigDecimal[] t = cumulative.getOrDefault(code, ZERO2);
            BigDecimal bal = balance(t[0], t[1], "DEBIT".equals(direction));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", Val.strOf(s.get("name")));
            item.put("amount", bal);

            switch (category) {
                case "ASSET", "COST" -> {           // 成本类余额 = 在制品，归入存货
                    if (bal.signum() != 0) assets.add(item);
                    totalAssets = totalAssets.add(bal);
                }
                case "LIABILITY" -> {
                    if (bal.signum() != 0) liabilities.add(item);
                    totalLiability = totalLiability.add(bal);
                }
                case "EQUITY" -> {
                    if (bal.signum() != 0) equities.add(item);
                    totalEquity = totalEquity.add(bal);
                }
                default -> { /* 损益类不进资产负债表，汇总进本年利润 */ }
            }
        }

        // 本年利润 = 全部收入 - 全部成本费用（累计口径）
        BigDecimal netProfit = netByCategory("INCOME", " AND v.account_period <= ?", p)
                .subtract(netSubject("6401", " AND v.account_period <= ?", p))
                .subtract(netSubject("6601", " AND v.account_period <= ?", p))
                .subtract(netSubject("6602", " AND v.account_period <= ?", p));
        Map<String, Object> profitItem = new LinkedHashMap<>();
        profitItem.put("code", "4103");
        profitItem.put("name", "本年利润（未结转）");
        profitItem.put("amount", netProfit);
        equities.add(profitItem);
        totalEquity = totalEquity.add(netProfit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", p);
        result.put("assets", assets);
        result.put("liabilities", liabilities);
        result.put("equities", equities);
        result.put("totalAssets", totalAssets);
        result.put("totalLiability", totalLiability);
        result.put("totalEquity", totalEquity);
        result.put("totalLiabilityEquity", totalLiability.add(totalEquity));
        result.put("difference", totalAssets.subtract(totalLiability).subtract(totalEquity));
        return result;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private static final BigDecimal[] ZERO2 = {BigDecimal.ZERO, BigDecimal.ZERO};

    /** 按科目汇总借 / 贷发生额；condition 必须是代码内常量拼接，参数用占位符传入 */
    private Map<String, BigDecimal[]> sums(String condition, String... args) {
        Map<String, BigDecimal[]> result = new HashMap<>();
        List<Map<String, Object>> rows = db.list(
                "SELECT e.subject_code AS code, SUM(e.debit) AS d, SUM(e.credit) AS c "
                        + "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id=e.voucher_id "
                        + "WHERE v.status='POSTED'" + condition + " GROUP BY e.subject_code", (Object[]) args);
        for (Map<String, Object> r : rows) {
            result.put(Val.strOf(r.get("code")),
                    new BigDecimal[]{Val.decOf(r.get("d")), Val.decOf(r.get("c"))});
        }
        return result;
    }

    private BigDecimal[] sumOne(String sql, Object... args) {
        Map<String, Object> r = db.one(sql, args);
        if (r == null) {
            return ZERO2;
        }
        // H2 的 SUM 别名可能被转成大写，这里按位置取值而不是按别名，兼容性更好
        Iterator<Object> it = r.values().iterator();
        BigDecimal d = it.hasNext() ? Val.decOf(it.next()) : BigDecimal.ZERO;
        BigDecimal c = it.hasNext() ? Val.decOf(it.next()) : BigDecimal.ZERO;
        return new BigDecimal[]{d, c};
    }

    /** 按科目类别汇总净额：收入类取贷-借，其余取借-贷 */
    private BigDecimal netByCategory(String category, String condition, String... args) {
        String sql = "SELECT COALESCE(SUM(e.debit),0) AS d, COALESCE(SUM(e.credit),0) AS c "
                + "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id=e.voucher_id "
                + "JOIN fin_subject s ON s.code=e.subject_code "
                + "WHERE v.status='POSTED' AND s.category=?" + condition;
        Object[] params = new Object[args.length + 1];
        params[0] = category;
        System.arraycopy(args, 0, params, 1, args.length);
        BigDecimal[] t = sumOne(sql, params);
        return "INCOME".equals(category) ? t[1].subtract(t[0]) : t[0].subtract(t[1]);
    }

    private BigDecimal netSubject(String code, String condition, String... args) {
        String sql = "SELECT COALESCE(SUM(e.debit),0) AS d, COALESCE(SUM(e.credit),0) AS c "
                + "FROM fin_voucher_entry e JOIN fin_voucher v ON v.id=e.voucher_id "
                + "WHERE v.status='POSTED' AND e.subject_code=?" + condition;
        Object[] params = new Object[args.length + 1];
        params[0] = code;
        System.arraycopy(args, 0, params, 1, args.length);
        BigDecimal[] t = sumOne(sql, params);
        return t[0].subtract(t[1]); // 费用类：借增贷减
    }

    /** 按科目余额方向计算余额：借方科目借-贷，贷方科目贷-借 */
    private static BigDecimal balance(BigDecimal debit, BigDecimal credit, boolean debitSide) {
        return debitSide ? debit.subtract(credit) : credit.subtract(debit);
    }

    private static Map<String, Object> line(String name, BigDecimal current, BigDecimal ytd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("current", current);
        m.put("ytd", ytd);
        return m;
    }

    private static String blankToCurrentPeriod(String period) {
        return (period == null || period.isBlank())
                ? LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")) : period;
    }

    private static LocalDate parseDate(String text, LocalDate fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(text.substring(0, Math.min(10, text.length())));
        } catch (Exception e) {
            throw new BizException("日期格式不正确，应为 yyyy-MM-dd");
        }
    }

    private static String dateText(Object v) {
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        return Val.strOf(v);
    }
}
