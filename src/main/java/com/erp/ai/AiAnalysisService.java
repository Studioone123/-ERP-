package com.erp.ai;

import com.erp.common.BizException;
import com.erp.common.CacheHub;
import com.erp.common.Db;
import com.erp.common.Val;
import com.erp.dashboard.DashboardService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 经营分析 —— 把 ERP 的真实业务数据交给大模型解读。
 *
 * <p><b>整体思路（面试可直接讲）</b>：
 * <ol>
 *   <li><b>先取数，再问模型</b>：LLM 不直接碰数据库。Service 用与看板完全相同的 SQL
 *       汇总出一份「事实清单」（metrics / 趋势 / 低库存 / 应收应付），再把它塞进 prompt。
 *       模型只做「读数据、写人话」，数值全部由系统给出，避免幻觉出假数字。</li>
 *   <li><b>双引擎降级</b>：配了 {@code erp.ai.api-key} 走 LLM（OpenAI 兼容协议）；
 *       没配 key、调用超时或被限流时，自动切到内置规则引擎——同样的数据，
 *       用阈值规则生成结论，功能永远可用。返回体里的 {@code source} 字段
 *       明确告诉前端这次是谁生成的，不藏着掖着。</li>
 *   <li><b>缓存复用</b>：分析结果挂进与看板相同的缓存区，由
 *       {@code CacheEvictInterceptor} 在任何写操作后统一清空——业务一变，
 *       下次分析自动重新生成；没变就复用结果，不重复烧 LLM 的 token 费用。</li>
 * </ol>
 */
@Service
public class AiAnalysisService {

    /** 支持的分析类型 → 中文名（也用于 prompt 里告诉模型「你在分析什么」） */
    public static final Map<String, String> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("overview", "经营总览");
        TYPES.put("sales", "销售分析");
        TYPES.put("inventory", "库存分析");
        TYPES.put("finance", "财务分析");
    }

    private final AiProperties props;
    private final LlmClient llm;
    private final DashboardService dashboardService;
    private final Db db;

    public AiAnalysisService(AiProperties props, LlmClient llm,
                             DashboardService dashboardService, Db db) {
        this.props = props;
        this.llm = llm;
        this.dashboardService = dashboardService;
        this.db = db;
    }

    /** 运维/前端状态卡用：当前会走哪个引擎 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", props.llmReady() ? "llm" : "local");
        m.put("modeLabel", props.llmReady() ? "大模型（" + props.getModel() + "）" : "本地规则引擎");
        m.put("model", props.llmReady() ? props.getModel() : "-");
        m.put("baseUrl", props.llmReady() ? props.getBaseUrl() : "-");
        m.put("enabled", props.isEnabled());
        m.put("types", TYPES);
        return m;
    }

    /**
     * 生成一份经营分析。
     * 缓存 key 含类型；任何写操作（下单/入库/过账…）都会使缓存失效，
     * 保证「分析看到的永远是最新业务」。
     */
    @Cacheable(cacheNames = CacheHub.DASHBOARD, key = "'ai:' + #type")
    public Map<String, Object> analyze(String type) {
        if (!TYPES.containsKey(type)) {
            throw new BizException("不支持的分析类型：" + type + "，可选：" + String.join("/", TYPES.keySet()));
        }

        Map<String, Object> facts = collectFacts(type);

        String content;
        String source;
        Optional<String> llmText = llm.chat(systemPrompt(), userPrompt(type, facts));
        if (llmText.isPresent()) {
            content = llmText.get();
            source = "llm";
        } else {
            content = ruleBased(type, facts);
            source = "local";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("typeLabel", TYPES.get(type));
        result.put("source", source);
        result.put("sourceLabel", "llm".equals(source)
                ? "大模型（" + props.getModel() + "）" : "本地规则引擎");
        result.put("content", content);
        result.put("facts", facts);
        result.put("generatedAt",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return result;
    }

    // ==================== 第一步：取数（只读，SQL 与看板同源） ====================

    private Map<String, Object> collectFacts(String type) {
        // 看板 summary 本身带缓存，这里复用不会多打一次数据库
        Map<String, Object> summary = dashboardService.summary();
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) summary.get("metrics");

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("本月销售额", moneyOf(metrics.get("monthSales")));
        facts.put("本月采购额", moneyOf(metrics.get("monthPurchase")));
        facts.put("库存总价值", moneyOf(metrics.get("stockValue")));
        facts.put("本月销售订单数", metrics.get("monthSalesOrders"));
        facts.put("本月采购订单数", metrics.get("monthPurchaseOrders"));
        facts.put("低库存商品数", metrics.get("lowStock"));
        facts.put("待审核采购单", metrics.get("pendingPurchase"));
        facts.put("待审核销售单", metrics.get("pendingSales"));

        switch (type) {
            case "overview" -> {
                facts.put("近7日趋势", summary.get("trend"));
                facts.put("热销商品TOP5", summary.get("topProducts"));
                facts.put("低库存明细", summary.get("lowStockList"));
                facts.putAll(arApFacts());
            }
            case "sales" -> {
                facts.put("近7日销售趋势", summary.get("trend"));
                facts.put("热销商品TOP5", summary.get("topProducts"));
                facts.put("客户总数", metrics.get("customers"));
                facts.put("应收未收余额", arApFacts().get("应收未收余额"));
                facts.put("逾期应收笔数", arApFacts().get("逾期应收笔数"));
            }
            case "inventory" -> {
                facts.put("低库存明细", summary.get("lowStockList"));
                facts.put("商品总数", metrics.get("products"));
                facts.put("近30天出入库", flowFacts());
            }
            case "finance" -> {
                facts.putAll(arApFacts());
                facts.put("本月凭证数", voucherFacts());
                facts.put("供应商总数", metrics.get("suppliers"));
                facts.put("客户总数", metrics.get("customers"));
            }
            default -> {
                // 类型已在入口处校验，不会到这里
            }
        }
        return facts;
    }

    /** 应收应付与逾期情况：财务健康度的核心事实 */
    private Map<String, Object> arApFacts() {
        Map<String, Object> m = new LinkedHashMap<>();
        BigDecimal ar = db.value(
                "SELECT COALESCE(SUM(amount - settled_amount),0) FROM fin_ar_ap "
                        + "WHERE bill_type='AR' AND status<>'CLOSED'", BigDecimal.class);
        BigDecimal ap = db.value(
                "SELECT COALESCE(SUM(amount - settled_amount),0) FROM fin_ar_ap "
                        + "WHERE bill_type='AP' AND status<>'CLOSED'", BigDecimal.class);
        Long overdueAr = db.value(
                "SELECT COUNT(*) FROM fin_ar_ap WHERE bill_type='AR' "
                        + "AND status<>'CLOSED' AND due_date < CURRENT_DATE", Long.class);
        Long overdueAp = db.value(
                "SELECT COUNT(*) FROM fin_ar_ap WHERE bill_type='AP' "
                        + "AND status<>'CLOSED' AND due_date < CURRENT_DATE", Long.class);
        m.put("应收未收余额", ar == null ? BigDecimal.ZERO : ar);
        m.put("应付未付余额", ap == null ? BigDecimal.ZERO : ap);
        m.put("逾期应收笔数", overdueAr == null ? 0L : overdueAr);
        m.put("逾期应付笔数", overdueAp == null ? 0L : overdueAp);
        return m;
    }

    private List<Map<String, Object>> flowFacts() {
        Timestamp from = Timestamp.valueOf(LocalDate.now().minusDays(30).atStartOfDay());
        return db.list(
                "SELECT biz_type, COUNT(*) AS cnt, COALESCE(SUM(ABS(change_qty)),0) AS qty "
                        + "FROM stock_flow WHERE created_at >= ? GROUP BY biz_type ORDER BY qty DESC", from);
    }

    private Long voucherFacts() {
        Timestamp monthStart = Timestamp.valueOf(LocalDate.now().withDayOfMonth(1).atStartOfDay());
        Long cnt = db.value("SELECT COUNT(*) FROM fin_voucher WHERE created_at >= ?", Long.class, monthStart);
        return cnt == null ? 0L : cnt;
    }

    private static Object moneyOf(Object v) {
        return v == null ? BigDecimal.ZERO : Val.decOf(v);
    }

    // ==================== 第二步：构造 prompt ====================

    private static String systemPrompt() {
        return """
                你是一名资深 ERP 经营分析顾问，正在解读一套中小企业进销存+财务系统的实时数据。
                要求：
                1. 只基于用户给出的数据下结论，严禁编造数据里没有的数字；
                2. 输出 Markdown，固定三个小节：## 总体判断、## 风险预警、## 行动建议；
                3. 总篇幅控制在 400 字以内，每条建议都要可执行（谁、做什么、为什么）；
                4. 金额单位是人民币元，可适当换算为「万」提高可读性。""";
    }

    private static String userPrompt(String type, Map<String, Object> facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("分析主题：").append(TYPES.get(type)).append('\n');
        sb.append("以下是从 ERP 数据库实时汇总的事实数据：\n\n");
        facts.forEach((k, v) -> sb.append("- ").append(k).append("：").append(v).append('\n'));
        sb.append("\n请基于以上数据输出").append(TYPES.get(type)).append("报告。");
        return sb.toString();
    }

    // ==================== 第三步：本地规则引擎（降级兜底，纯函数便于单测） ====================

    /**
     * 规则引擎：与 LLM 消费同一份 facts，输出同样结构的 Markdown。
     * 写成静态纯函数，单测不需要 Spring 容器和数据库。
     */
    @SuppressWarnings("unchecked")
    public static String ruleBased(String type, Map<String, Object> facts) {
        BigDecimal sales = dec(facts.get("本月销售额"));
        BigDecimal purchase = dec(facts.get("本月采购额"));
        BigDecimal stock = dec(facts.get("库存总价值"));
        long lowStock = lng(facts.get("低库存商品数"));
        long pendingP = lng(facts.get("待审核采购单"));
        long pendingS = lng(facts.get("待审核销售单"));

        StringBuilder judge = new StringBuilder();
        StringBuilder risk = new StringBuilder();
        StringBuilder action = new StringBuilder();

        // —— 总体判断 ——
        judge.append("- 本月销售额 ").append(wan(sales)).append("，采购额 ").append(wan(purchase))
                .append("，库存总价值 ").append(wan(stock)).append("。\n");
        if (sales.signum() > 0 && purchase.signum() > 0) {
            BigDecimal ratio = sales.divide(purchase, 2, RoundingMode.HALF_UP);
            judge.append("- 销采比 ").append(ratio).append("：")
                    .append(ratio.compareTo(BigDecimal.ONE) >= 0
                            ? "销售规模跑赢进货节奏，资金周转方向健康。" : "采购投入大于销售变现，需关注资金占用。")
                    .append('\n');
        }
        if ("sales".equals(type)) {
            judge.append("- 客户总数 ").append(lng(facts.get("客户总数"))).append(" 家，");
            Object top = facts.get("热销商品TOP5");
            if (top instanceof List<?> l && !l.isEmpty()) {
                judge.append("热销榜首为「").append(((Map<String, Object>) l.get(0)).get("name")).append("」。");
            } else {
                judge.append("近期暂无成交商品。");
            }
            judge.append('\n');
        }
        if ("inventory".equals(type)) {
            judge.append("- 在库商品 ").append(lng(facts.get("商品总数"))).append(" 种，")
                    .append(lowStock > 0 ? "其中 " + lowStock + " 种已触及安全库存线。" : "库存水位整体安全。")
                    .append('\n');
        }
        if ("finance".equals(type)) {
            judge.append("- 本月已生成凭证 ").append(lng(facts.get("本月凭证数"))).append(" 张，业财联动运转中。\n");
        }

        // —— 风险预警 ——
        if (lowStock > 0) {
            risk.append("- **缺货风险**：").append(lowStock).append(" 种商品低于安全库存，销售可能断货。\n");
        }
        long overdueAr = lng(facts.get("逾期应收笔数"));
        if (overdueAr > 0) {
            risk.append("- **回款风险**：").append(overdueAr).append(" 笔应收已逾期，余额合计 ")
                    .append(wan(dec(facts.get("应收未收余额")))).append("。\n");
        }
        long overdueAp = lng(facts.get("逾期应付笔数"));
        if (overdueAp > 0) {
            risk.append("- **信用风险**：").append(overdueAp).append(" 笔应付已逾期，可能影响供应商合作关系。\n");
        }
        if (pendingP + pendingS > 0) {
            risk.append("- **流程积压**：").append(pendingP).append(" 张采购单、")
                    .append(pendingS).append(" 张销售单待审核，业务尚未闭环。\n");
        }
        if (risk.length() == 0) {
            risk.append("- 未发现明显风险点，各项阈值均在安全区间。\n");
        }

        // —— 行动建议 ——
        if (lowStock > 0) {
            action.append("- 采购专员：今日内对低库存商品发起补货单，优先处理有销售在途的品项。\n");
        }
        if (overdueAr > 0) {
            action.append("- 销售/财务：逐笔跟进逾期应收，电话催收并在系统登记收付款计划。\n");
        }
        if (overdueAp > 0) {
            action.append("- 财务：安排逾期应付的付款排期，避免产生滞纳与信任损失。\n");
        }
        if (pendingP + pendingS > 0) {
            action.append("- 各业务负责人：清理 ").append(pendingP + pendingS)
                    .append(" 张待审核单据，让库存与凭证及时联动。\n");
        }
        if (sales.signum() == 0) {
            action.append("- 本月尚无销售入账：优先激活老客户复购，并把报价单转成正式订单。\n");
        }
        if (action.length() == 0) {
            action.append("- 保持当前节奏：按周复盘看板指标，重点关注库存周转与回款速度。\n");
        }

        return "## 总体判断\n" + judge + "\n## 风险预警\n" + risk + "\n## 行动建议\n" + action;
    }

    private static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO : Val.decOf(v);
    }

    private static long lng(Object v) {
        return v == null ? 0L : Val.decOf(v).longValue();
    }

    /** 大额转「x.x万」，小额原样，提升报告可读性 */
    private static String wan(BigDecimal v) {
        if (v.abs().compareTo(new BigDecimal("10000")) >= 0) {
            return v.divide(new BigDecimal("10000"), 1, RoundingMode.HALF_UP) + " 万元";
        }
        return v.setScale(2, RoundingMode.HALF_UP) + " 元";
    }
}
