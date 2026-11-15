package com.erp.finance;

import com.erp.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 会计凭证服务 —— 对应金蝶 K/3「总账系统 → 凭证管理」。
 *
 * <h3>一、会计原理（复式记账）</h3>
 * 每一笔经济业务都要在两个或两个以上科目登记，且「借方合计 = 贷方合计」，
 * 简称「有借必有贷、借贷必相等」。例如采购入库 10,000 元：
 * <pre>
 *   借：1405 库存商品   10,000      （资产增加记借方）
 *   贷：2202 应付账款   10,000      （负债增加记贷方）
 * </pre>
 * 本类不硬编码任何业务，只负责「凭证」这一本账；具体某类业务该借谁贷谁，
 * 由 {@link com.erp.common.BizPostingService} 里的凭证模板决定 —— 这正是金蝶「业务凭证模板」的设计。
 *
 * <h3>二、状态机</h3>
 * <pre>
 *   手工录入 → DRAFT（草稿，可改可删，不进账簿）
 *            → POSTED（已审核过账，不可修改，参与余额表 / 报表计算）
 *   业务生成 → 直接 POSTED（业务单据已经审核过，凭证无需二次录入）
 * </pre>
 * 金蝶里「审核」与「过账」是两个动作，本系统简化为一步 post()，
 * 但保留了「已过账凭证不可篡改」这条财务红线。
 *
 * <h3>三、为什么余额不落库</h3>
 * 金蝶会维护科目余额表并在过账时增量更新（性能优化）。这里改用
 * 「从已过账分录实时汇总」，少一张表、少一次更新，就少一处可能出现
 * 账实不符的地方；中小企业账套数据量下性能完全可接受。
 * 汇总逻辑见 {@link FinanceReportService}。
 */
@Service
public class VoucherService {

    /** 会计期间格式 YYYY-MM，结账、报表都以它为单位 */
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final Db db;
    private final NoGenerator noGenerator;
    private final LogService logService;

    public VoucherService(Db db, NoGenerator noGenerator, LogService logService) {
        this.db = db;
        this.noGenerator = noGenerator;
        this.logService = logService;
    }

    /**
     * 一行分录的数据载体。用 record 而不是 Map，是为了让「科目、摘要、借方、贷方」
     * 四个要素在编译期就有类型约束，业务模板构造分录时不容易漏字段。
     */
    public record VoucherLine(String subjectCode, String summary, BigDecimal debit, BigDecimal credit) {
        /** 借方分录的快捷构造 */
        public static VoucherLine debit(String subject, String summary, BigDecimal amount) {
            return new VoucherLine(subject, summary, amount, BigDecimal.ZERO);
        }

        /** 贷方分录的快捷构造 */
        public static VoucherLine credit(String subject, String summary, BigDecimal amount) {
            return new VoucherLine(subject, summary, BigDecimal.ZERO, amount);
        }
    }

    // =====================================================================
    // 会计科目
    // =====================================================================

    /** 科目列表：按编码排序，末级科目才能被凭证引用 */
    public List<Map<String, Object>> subjects() {
        return db.list("SELECT * FROM fin_subject ORDER BY code");
    }

    /**
     * 新增会计科目。科目编码采用金蝶常见的 4 位一级 + 2 位明细，
     * 例如 1002 银行存款、100201 银行存款-工行。
     */
    @Transactional
    public long createSubject(Map<String, Object> body) {
        String code = Db.str(body, "code");
        String name = Db.str(body, "name");
        String category = Db.str(body, "category");
        String direction = Db.str(body, "direction");
        if (code.isEmpty() || name.isEmpty()) {
            throw new BizException("科目编码和名称不能为空");
        }
        // 白名单校验：避免脏数据进入报表分类逻辑
        if (!List.of("ASSET", "LIABILITY", "EQUITY", "COST", "INCOME", "EXPENSE").contains(category)) {
            throw new BizException("科目类别不合法");
        }
        if (!List.of("DEBIT", "CREDIT").contains(direction)) {
            throw new BizException("余额方向只能是 DEBIT 或 CREDIT");
        }
        Integer exists = db.value("SELECT COUNT(*) FROM fin_subject WHERE code=?", Integer.class, code);
        if (exists != null && exists > 0) {
            throw new BizException("科目编码 " + code + " 已存在");
        }
        long id = db.insert("INSERT INTO fin_subject(code,name,category,direction,parent_code,is_leaf,status,remark) "
                        + "VALUES(?,?,?,?,?,?,1,?)",
                code, name, category, direction, Db.str(body, "parent_code"),
                Db.integer(body, "is_leaf", 1), Db.str(body, "remark"));
        logService.record("新增会计科目", code + " " + name);
        return id;
    }

    // =====================================================================
    // 凭证查询
    // =====================================================================

    /**
     * 凭证列表。支持按状态、会计期间、来源类型过滤，关键词匹配凭证号 / 摘要 / 来源单号。
     * 关联子查询统计分录行数，方便列表上直接看出凭证规模。
     */
    public List<Map<String, Object>> list(String status, String period, String keyword, String sourceType) {
        StringBuilder sql = new StringBuilder(
                "SELECT v.*, (SELECT COUNT(*) FROM fin_voucher_entry e WHERE e.voucher_id=v.id) AS entry_count "
                        + "FROM fin_voucher v WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND v.status=?");
            args.add(status);
        }
        if (period != null && !period.isBlank()) {
            sql.append(" AND v.account_period=?");
            args.add(period);
        }
        if (sourceType != null && !sourceType.isBlank()) {
            sql.append(" AND v.source_type=?");
            args.add(sourceType);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (v.voucher_no LIKE ? OR v.summary LIKE ? OR v.biz_no LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            Collections.addAll(args, kw, kw, kw);
        }
        sql.append(" ORDER BY v.voucher_date DESC, v.id DESC LIMIT 500");
        return normalize(db.list(sql.toString(), args.toArray()));
    }

    /** 凭证详情：主表 + 按行号排序的分录（金蝶叫「凭证分录」） */
    public Map<String, Object> detail(long id) {
        Map<String, Object> voucher = require(id);
        Map<String, Object> result = new LinkedHashMap<>(normalize(voucher));
        result.put("entries", db.list(
                "SELECT * FROM fin_voucher_entry WHERE voucher_id=? ORDER BY line_no, id", id));
        return result;
    }

    // =====================================================================
    // 手工凭证：录入 → 审核过账
    // =====================================================================

    /**
     * 手工新增凭证，落库为 DRAFT 草稿。
     * 前端传参示例：
     * <pre>
     * {
     *   "voucher_date": "2026-11-10", "voucher_word": "记", "summary": "支付办公室租金",
     *   "entries": [
     *     {"subject_code":"6602","summary":"办公室租金","debit":3000,"credit":0},
     *     {"subject_code":"1002","summary":"办公室租金","debit":0,"credit":3000}
     *   ]
     * }
     * </pre>
     */
    @Transactional
    public long create(Map<String, Object> body) {
        LocalDate date = parseDate(Db.str(body, "voucher_date"));
        String word = Db.str(body, "voucher_word").isEmpty() ? "记" : Db.str(body, "voucher_word");
        String summary = Db.str(body, "summary");
        int attachmentNo = Db.integer(body, "attachment_no", 0);
        List<VoucherLine> lines = parseLines(body);

        // insertVoucher 会做「科目存在 / 借贷平衡 / 期间未结账」三项硬校验
        return insertVoucher("MANUAL", "", date, word, summary, lines, "DRAFT", attachmentNo);
    }

    /**
     * 审核过账：DRAFT → POSTED。
     * 过账前重新复核借贷平衡（防止草稿被绕过接口直接改库），
     * 过账后凭证即成为法定会计档案，系统不再允许修改或删除。
     */
    @Transactional
    public void post(long id) {
        Map<String, Object> voucher = require(id);
        if (!"DRAFT".equals(Val.strOf(voucher.get("status")))) {
            throw new BizException("只有草稿状态的凭证可以审核过账");
        }
        assertPeriodOpen(Val.strOf(voucher.get("account_period")));
        BigDecimal debit = sumLines(id, "debit");
        BigDecimal credit = sumLines(id, "credit");
        if (debit.compareTo(credit) != 0) {
            throw new BizException("借贷不平衡，不能过账（借 " + debit + " / 贷 " + credit + "）");
        }
        db.exec("UPDATE fin_voucher SET status='POSTED', posted_by=?, posted_at=CURRENT_TIMESTAMP WHERE id=?",
                UserContext.get() == null ? "admin" : UserContext.get(), id);
        logService.record("凭证审核过账", Val.strOf(voucher.get("voucher_no")) + " 金额 " + debit);
    }

    /** 删除草稿凭证；已过账凭证按财务规范只可作废不可物理删除，这里直接拒绝 */
    @Transactional
    public void delete(long id) {
        Map<String, Object> voucher = require(id);
        if (!"DRAFT".equals(Val.strOf(voucher.get("status")))) {
            throw new BizException("已过账凭证不能删除，请联系财务按规范处理");
        }
        db.exec("DELETE FROM fin_voucher_entry WHERE voucher_id=?", id);
        db.exec("DELETE FROM fin_voucher WHERE id=?", id);
        logService.record("删除凭证草稿", Val.strOf(voucher.get("voucher_no")));
    }

    // =====================================================================
    // 业务凭证（业财一体化入口）
    // =====================================================================

    /**
     * 由业务单据直接生成「已过账」凭证。
     * 这是金蝶「业务系统自动生成凭证」的实现点：采购 / 销售 / 收付款 / 生产
     * 只需要描述「借什么、贷什么」，不必关心凭证表结构。
     *
     * @param sourceType 来源类型，写入凭证供联查（如 PURCHASE_IN）
     * @param bizNo      来源业务单号，实现「凭证 → 单据」反查
     * @param status     调用方通常传 POSTED；期初建账单据也走这里
     */
    @Transactional
    public long postGenerated(String sourceType, String bizNo, LocalDate date,
                              String word, String summary, List<VoucherLine> lines, String status) {
        return insertVoucher(sourceType, bizNo, date, word, summary, lines, status, 0);
    }

    // =====================================================================
    // 内部实现
    // =====================================================================

    /**
     * 凭证落库的唯一通道。所有校验集中在这里，手工凭证与自动凭证共用，
     * 避免出现「手工管得严、自动凭证能绕过去」的漏洞。
     */
    private long insertVoucher(String sourceType, String bizNo, LocalDate date, String word,
                               String summary, List<VoucherLine> lines, String status, int attachmentNo) {
        if (lines == null || lines.size() < 2) {
            throw new BizException("一张凭证至少需要两条分录");
        }
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (VoucherLine line : lines) {
            BigDecimal d = line.debit() == null ? BigDecimal.ZERO : line.debit();
            BigDecimal c = line.credit() == null ? BigDecimal.ZERO : line.credit();
            if (d.signum() < 0 || c.signum() < 0) {
                throw new BizException("分录金额不能为负数");
            }
            // 同一行不允许既有借方又有贷方，也不允许两个都是 0
            if (d.signum() > 0 && c.signum() > 0) {
                throw new BizException("同一行分录不能同时登记借方和贷方");
            }
            if (d.signum() == 0 && c.signum() == 0) {
                throw new BizException("分录借贷金额不能同时为 0");
            }
            requireSubject(line.subjectCode());
            totalDebit = totalDebit.add(d);
            totalCredit = totalCredit.add(c);
        }
        // 用 compareTo 而不是 equals：BigDecimal 的 equals 会连小数位一起比较（10.0 != 10.00）
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new BizException("借贷不平衡：借方 " + totalDebit + "，贷方 " + totalCredit);
        }

        String period = date.format(PERIOD_FMT);
        assertPeriodOpen(period);
        ensurePeriod(period);

        long id = db.insert(
                "INSERT INTO fin_voucher(voucher_no,voucher_date,account_period,voucher_word,summary,"
                        + "source_type,biz_no,status,total_debit,total_credit,attachment_no,created_by,"
                        + "created_at,posted_by,posted_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?,?)",
                noGenerator.voucher(), Date.valueOf(date), period, word, summary,
                sourceType, bizNo, status, totalDebit, totalCredit, attachmentNo,
                UserContext.get() == null ? "system" : UserContext.get(),
                "POSTED".equals(status) ? (UserContext.get() == null ? "system" : UserContext.get()) : null,
                "POSTED".equals(status) ? new java.sql.Timestamp(System.currentTimeMillis()) : null);

        int lineNo = 1;
        for (VoucherLine line : lines) {
            Map<String, Object> subject = requireSubject(line.subjectCode());
            db.exec("INSERT INTO fin_voucher_entry(voucher_id,line_no,summary,subject_code,subject_name,debit,credit) "
                            + "VALUES(?,?,?,?,?,?,?)",
                    id, lineNo++, line.summary() == null ? summary : line.summary(),
                    line.subjectCode(), Val.strOf(subject.get("name")),
                    line.debit() == null ? BigDecimal.ZERO : line.debit(),
                    line.credit() == null ? BigDecimal.ZERO : line.credit());
        }
        return id;
    }

    /** 校验科目存在、启用且为末级；返回科目行供写入冗余名称 */
    private Map<String, Object> requireSubject(String code) {
        Map<String, Object> row = db.one("SELECT * FROM fin_subject WHERE code=?", code);
        if (row == null) {
            throw new BizException("会计科目 " + code + " 不存在，请先在科目表中维护");
        }
        if (Val.intOf(row.get("is_leaf")) != 1 || Val.intOf(row.get("status")) != 1) {
            throw new BizException("科目 " + code + " 不是可用的末级科目");
        }
        return row;
    }

    /** 已结账期间不允许再产生凭证（金蝶「期末结账」的硬性控制） */
    private void assertPeriodOpen(String period) {
        Map<String, Object> row = db.one("SELECT status FROM fin_period WHERE account_period=?", period);
        if (row != null && "CLOSED".equals(Val.strOf(row.get("status")))) {
            throw new BizException("会计期间 " + period + " 已结账，不能新增或修改凭证");
        }
    }

    /** 期间记录不存在时自动补一条 OPEN，避免业务跨月时报错 */
    private void ensurePeriod(String period) {
        Integer c = db.value("SELECT COUNT(*) FROM fin_period WHERE account_period=?", Integer.class, period);
        if (c == null || c == 0) {
            db.exec("INSERT INTO fin_period(account_period,status) VALUES(?,'OPEN')", period);
        }
    }

    /** 把前端 entries 数组转换成强类型分录，并做基础格式校验 */
    private List<VoucherLine> parseLines(Map<String, Object> body) {
        Object raw = body.get("entries");
        if (!(raw instanceof List<?> list) || list.size() < 2) {
            throw new BizException("请至少填写两条凭证分录");
        }
        List<VoucherLine> lines = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new BizException("分录格式不正确");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) m;
            String code = Db.str(item, "subject_code");
            if (code.isEmpty()) {
                throw new BizException("分录中存在未选择科目");
            }
            lines.add(new VoucherLine(code, Db.str(item, "summary"),
                    Db.decimal(item, "debit"), Db.decimal(item, "credit")));
        }
        return lines;
    }

    private LocalDate parseDate(String text) {
        if (text.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(text.substring(0, Math.min(10, text.length())));
        } catch (Exception e) {
            throw new BizException("凭证日期格式不正确，应为 yyyy-MM-dd");
        }
    }

    private BigDecimal sumLines(long voucherId, String column) {
        // column 只可能是代码内常量 "debit" / "credit"，无注入风险
        BigDecimal v = db.value("SELECT COALESCE(SUM(" + column + "),0) FROM fin_voucher_entry WHERE voucher_id=?",
                BigDecimal.class, voucherId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private Map<String, Object> require(long id) {
        Map<String, Object> row = db.one("SELECT * FROM fin_voucher WHERE id=?", id);
        if (row == null) {
            throw new BizException("凭证不存在");
        }
        return row;
    }

    /**
     * JDBC 返回的 DATE 在不同驱动下可能是 java.sql.Date / LocalDate，
     * Jackson 又可能把它序列化成时间戳。统一转成 "yyyy-MM-dd" 字符串，
     * 前端无需关心驱动差异。
     */
    private static List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(normalize(row));
        }
        return result;
    }

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
