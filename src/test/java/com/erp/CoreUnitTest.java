package com.erp;

import com.erp.ai.AiAnalysisService;
import com.erp.common.PageResult;
import com.erp.common.PageSupport;
import com.erp.common.PasswordService;
import com.erp.common.R;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单元测试：不启动 Spring 容器，只验证工具类与算法的边界行为。
 *
 * <p>这类测试的价值是「快且稳定」——不依赖数据库、不依赖网络，
 * 适合放进 CI 每次提交都跑。业务链路的验证放在 {@link ErpIntegrationTest}。
 */
class CoreUnitTest {

    // ---------------- 分页工具 ----------------

    @Test
    void slice_returnsExpectedPageAndTotals() {
        List<Integer> all = List.of(1, 2, 3, 4, 5, 6, 7);
        PageResult<Integer> p = PageSupport.slice(all, 2, 3);
        assertEquals(List.of(4, 5, 6), p.getList());
        assertEquals(7, p.getTotal());
        assertEquals(2, p.getPage());
        assertEquals(3, p.getSize());
        assertEquals(3, p.getPages(), "7 条数据每页 3 条应为 3 页");
    }

    @Test
    void slice_outOfRangeReturnsEmptyInsteadOfException() {
        PageResult<Integer> p = PageSupport.slice(List.of(1, 2), 99, 10);
        assertTrue(p.getList().isEmpty());
        assertEquals(2, p.getTotal());
    }

    @Test
    void slice_resultIsPlainArrayListNotSubListView() {
        // 回归测试：曾经把 ArrayList$SubList 写进 Redis 缓存，Jackson 无法反序列化，
        // 导致看板在缓存命中时报错。这里锁死「返回值必须是可独立序列化的普通 List」。
        List<Integer> src = new ArrayList<>(List.of(1, 2, 3, 4));
        PageResult<Integer> p = PageSupport.slice(src, 1, 2);
        assertEquals(ArrayList.class, p.getList().getClass());
    }

    @Test
    void normalize_clampsIllegalValues() {
        assertArrayEquals(new int[]{1, 20}, PageSupport.normalize(null, null));
        assertArrayEquals(new int[]{1, 20}, PageSupport.normalize(-5, 0));
        assertArrayEquals(new int[]{3, PageSupport.MAX_SIZE}, PageSupport.normalize(3, 999999));
    }

    @Test
    void reply_withoutPageKeepsLegacyArrayResponse() {
        // 向后兼容的核心断言：不传 page 时返回的仍是裸 List，前端无需改动
        List<Map<String, Object>> rows = List.of(Map.of("id", 1));
        R r = PageSupport.reply(rows, null, null);
        assertEquals(0, r.code());
        assertSame(rows, r.data());
        assertTrue(r.data() instanceof List);
    }

    // ---------------- 密码哈希 ----------------

    @Test
    void bcrypt_encodesDifferentlyButMatches() {
        PasswordService service = new PasswordService(null); // encode/matches 不访问数据库
        String a = service.encode("admin123");
        String b = service.encode("admin123");
        assertNotEquals(a, b, "BCrypt 内置随机 salt，两次编码结果必须不同");
        assertTrue(a.startsWith("$2"));
        assertTrue(service.matches("admin123", a));
        assertFalse(service.matches("wrong", a));
        assertFalse(service.isLegacy(a));
    }

    @Test
    void legacySha256HashStillVerifies() {
        // 老库里的 admin 是固定盐 SHA-256；升级后首次登录仍必须能通过，否则用户会被锁死
        PasswordService service = new PasswordService(null);
        String legacy = com.erp.common.Hash.sha256("admin123");
        assertTrue(service.matches("admin123", legacy));
        assertFalse(service.matches("admin1234", legacy));
        assertTrue(service.isLegacy(legacy), "应识别出需要平滑升级的老哈希");
    }

    @Test
    void decimalArithmeticUsesBigDecimalNotDouble() {
        // 财务金额必须用 BigDecimal：0.1+0.2 用 double 会得到 0.30000000000000004
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < 10; i++) {
            sum = sum.add(new BigDecimal("0.1"));
        }
        assertEquals(0, sum.compareTo(BigDecimal.ONE));
    }

    // ---------------- AI 规则引擎（LLM 不可用时的降级兜底） ----------------

    private static Map<String, Object> aiFacts() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("本月销售额", new BigDecimal("125000"));
        facts.put("本月采购额", new BigDecimal("80000"));
        facts.put("库存总价值", new BigDecimal("300000"));
        facts.put("低库存商品数", 3L);
        facts.put("待审核采购单", 1L);
        facts.put("待审核销售单", 2L);
        facts.put("应收未收余额", new BigDecimal("45000"));
        facts.put("逾期应收笔数", 2L);
        return facts;
    }

    @Test
    void ruleEngine_generatesThreeSections() {
        // 规则引擎是纯函数：不依赖 Spring / 数据库 / 网络，必须永远能产出完整报告
        String md = AiAnalysisService.ruleBased("overview", aiFacts());
        assertTrue(md.contains("## 总体判断"));
        assertTrue(md.contains("## 风险预警"));
        assertTrue(md.contains("## 行动建议"));
        assertTrue(md.contains("12.5 万元"), "大额应换算成「万」提升可读性");
    }

    @Test
    void ruleEngine_flagsRisksFromFacts() {
        String md = AiAnalysisService.ruleBased("overview", aiFacts());
        assertTrue(md.contains("缺货风险"), "低库存 > 0 必须触发缺货预警");
        assertTrue(md.contains("回款风险"), "逾期应收 > 0 必须触发回款预警");
        assertTrue(md.contains("催收"), "风险必须对应到可执行的建议动作");
    }

    @Test
    void ruleEngine_healthyDataHasNoFalseAlarm() {
        Map<String, Object> facts = aiFacts();
        facts.put("低库存商品数", 0L);
        facts.put("逾期应收笔数", 0L);
        facts.put("逾期应付笔数", 0L);
        facts.put("待审核采购单", 0L);
        facts.put("待审核销售单", 0L);
        String md = AiAnalysisService.ruleBased("finance", facts);
        assertTrue(md.contains("未发现明显风险"), "数据健康时不应制造焦虑");
    }

    @Test
    void ruleEngine_zeroSalesSuggestsAction() {
        Map<String, Object> facts = aiFacts();
        facts.put("本月销售额", BigDecimal.ZERO);
        String md = AiAnalysisService.ruleBased("sales", facts);
        assertTrue(md.contains("本月尚无销售入账"));
    }
}
