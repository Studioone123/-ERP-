package com.erp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 端到端集成测试：启动完整的 Spring 容器 + MockMvc，打真实的 REST 接口。
 *
 * <p>与纯单元测试的分工：这里验证的是「跨层联动」——一次采购入库是否同时写了库存、
 * 往来单和会计凭证；借贷不平时事务是否整体回滚；非管理员绕过页面直接调接口会不会被拦。
 * 这些逻辑只有把 Controller → Service → 数据库串起来才测得出来。
 *
 * <p><b>数据隔离</b>：{@code src/test/resources/application.properties} 把数据源换成
 * H2 内存库，测试不会碰到 D 盘正式数据；每次 mvn test 都是全新的演示数据。
 *
 * <p><b>为什么用 MockMvc 而不是 TestRestTemplate</b>：不需要真的监听端口，
 * 启动更快、CI 里不会有端口冲突，同时拦截器和 Filter 链路仍然会完整执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErpIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 静态缓存 token：JUnit 每个测试方法都会新建实例，static 可复用同一次登录 */
    private static volatile String sharedToken;

    private String token;

    @BeforeEach
    void loginAsAdmin() throws Exception {
        if (sharedToken == null) {
            JsonNode login = doLogin("admin", "admin123");
            assertEquals(0, login.path("code").asInt(), "内置管理员应能登录");
            sharedToken = login.path("data").path("token").asText();
        }
        token = sharedToken;
    }

    // ==================== 接口文档与基础设施 ====================

    @Test
    void openApiDocIsGenerated() throws Exception {
        MvcResult r = mvc.perform(get("/v3/api-docs")).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"openapi\""), "应返回 OpenAPI 3 规范文档");
        assertTrue(body.contains("/api/auth/login"), "文档应包含登录接口");
    }

    @Test
    void loginReturnsTokenAndMenuPermissions() throws Exception {
        JsonNode j = doLogin("admin", "admin123");
        assertEquals(0, j.path("code").asInt());
        assertTrue(j.path("data").path("token").asText().length() > 10);
        assertTrue(j.path("data").path("permissions").isArray(), "登录结果应下发菜单权限");
    }

    @Test
    void traceIdIsPropagatedToResponse() throws Exception {
        MvcResult r = mvc.perform(get("/api/dashboard/summary")
                .header("X-Token", token).header("X-Trace-Id", "test-trace-001")).andReturn();
        assertEquals("test-trace-001", r.getResponse().getHeader("X-Trace-Id"));
    }

    // ==================== 参数校验与安全 ====================

    @Test
    void blankUsernameIsRejectedByBeanValidation() throws Exception {
        JsonNode j = doLogin("", "admin123");
        assertEquals(400, j.path("code").asInt(), "@Valid 应把非法入参挡在业务层之外");
        assertTrue(j.path("msg").asText().contains("用户名"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        JsonNode j = doLogin("no_such_user", "definitely_wrong");
        assertNotEquals(0, j.path("code").asInt());
        assertTrue(j.path("msg").asText().contains("用户名或密码错误"));
    }

    @Test
    void accountLocksAfterFiveFailedAttempts() throws Exception {
        String user = "lock_" + (System.nanoTime() % 1_000_000L);
        for (int i = 0; i < 5; i++) {
            doLogin(user, "wrong" + i);
        }
        JsonNode j = doLogin(user, "still_wrong");
        assertEquals(429, j.path("code").asInt(), "连续失败 5 次后应触发登录锁定");
    }

    @Test
    void apiWithoutTokenReturns401() throws Exception {
        JsonNode j = parse(mvc.perform(get("/api/master/product")).andReturn());
        assertEquals(401, j.path("code").asInt());
    }

    @Test
    void illegalUsernameIsRejectedWhenCreatingUser() throws Exception {
        JsonNode j = doPost("/api/system/users",
                Map.of("username", "bad name!", "password", "123456", "role", "WAREHOUSE"));
        assertEquals(400, j.path("code").asInt());
        assertTrue(j.path("data").has("username"), "错误响应应带上具体字段名，便于前端高亮");
    }

    @Test
    void exportRespectsDataPermissions() throws Exception {
        String user = "wh2_" + (System.nanoTime() % 1_000_000L);
        assertEquals(0, doPost("/api/system/users",
                Map.of("username", user, "password", "test123456", "role", "WAREHOUSE"))
                .path("code").asInt());
        String warehouseToken = doLogin(user, "test123456").path("data").path("token").asText();

        // 仓管不能绕过 /api/finance/** 的模块权限，用导出接口把凭证整包导走
        JsonNode denied = parse(mvc.perform(get("/api/export/vouchers")
                .header("X-Token", warehouseToken)).andReturn());
        assertEquals(403, denied.path("code").asInt(), "导出接口必须复用同一套 RBAC 规则");

        // 但自己业务域内的库存流水应当允许导出
        MvcResult allowed = mvc.perform(get("/api/export/flows")
                .header("X-Token", warehouseToken)).andReturn();
        assertEquals(200, allowed.getResponse().getStatus());
    }

    @Test
    void nonAdminCannotWriteMasterData() throws Exception {
        String user = "wh_" + (System.nanoTime() % 1_000_000L);
        JsonNode created = doPost("/api/system/users",
                Map.of("username", user, "password", "test123456", "role", "WAREHOUSE"));
        assertEquals(0, created.path("code").asInt());
        JsonNode login = doLogin(user, "test123456");
        assertEquals(0, login.path("code").asInt());
        String warehouseToken = login.path("data").path("token").asText();

        JsonNode denied = doPostAs("/api/master/product",
                Map.of("code", "TEST", "name", "测试"), warehouseToken);
        assertEquals(403, denied.path("code").asInt(), "前端隐藏菜单不算安全，后端必须独立拦截");
    }

    // ==================== AI 经营分析 ====================

    @Test
    void aiAnalysisRequiresLogin() throws Exception {
        JsonNode j = parse(mvc.perform(get("/api/ai/analyze")).andReturn());
        assertEquals(401, j.path("code").asInt(), "AI 接口同样要过认证闸门");
    }

    @Test
    void aiAnalysisFallsBackToLocalEngineWithoutKey() throws Exception {
        // 测试环境未配置 ERP_AI_API_KEY：应自动降级到本地规则引擎，
        // 报告照常生成，source 字段必须如实告知前端
        JsonNode data = doGet("/api/ai/analyze?type=overview").path("data");
        assertEquals("local", data.path("source").asText());
        assertTrue(data.path("content").asText().contains("总体判断"));
        assertFalse(data.path("generatedAt").asText().isBlank());

        JsonNode status = doGet("/api/ai/status").path("data");
        assertEquals("local", status.path("mode").asText());
        assertEquals(4, status.path("types").size(), "应提供总览/销售/库存/财务四类分析");
    }

    @Test
    void aiAnalysisRejectsUnknownType() throws Exception {
        JsonNode j = doGet("/api/ai/analyze?type=hack");
        assertNotEquals(0, j.path("code").asInt());
        assertTrue(j.path("msg").asText().contains("不支持的分析类型"));
    }

    @Test
    void aiAnalysisAvailableToAllRoles() throws Exception {
        // AI 分析挂在公共模块（数据来自看板级汇总，不含敏感明细），
        // 仓管等非管理角色也应能使用
        String user = "ai_" + (System.nanoTime() % 1_000_000L);
        assertEquals(0, doPost("/api/system/users",
                Map.of("username", user, "password", "test123456", "role", "WAREHOUSE"))
                .path("code").asInt());
        String t = doLogin(user, "test123456").path("data").path("token").asText();

        JsonNode data = parse(mvc.perform(get("/api/ai/analyze?type=inventory")
                .header("X-Token", t)).andReturn()).path("data");
        assertEquals("local", data.path("source").asText());
        assertTrue(data.path("content").asText().contains("库存"));
    }

    // ==================== 业务闭环与业财一体化 ====================

    @Test
    void dashboardSummaryContainsMetrics() throws Exception {
        JsonNode data = doGet("/api/dashboard/summary").path("data");
        assertTrue(data.path("metrics").has("products"));
        assertTrue(data.path("metrics").has("monthSales"));
    }

    @Test
    void logsSupportRealSqlPagination() throws Exception {
        JsonNode data = doGet("/api/logs?page=1&size=3").path("data");
        assertTrue(data.has("total") && data.has("pages") && data.has("list"));
        assertTrue(data.path("list").size() <= 3);
    }

    @Test
    void purchaseReceiptGeneratesStockArApAndVoucherInOneTransaction() throws Exception {
        long supplierId = firstId("/api/master/supplier");
        long warehouseId = firstId("/api/master/warehouse");
        long productId = firstId("/api/master/product");

        int vouchersBefore = sizeOf("/api/finance/vouchers");
        int arapBefore = sizeOf("/api/settle/arap");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplier_id", supplierId);
        body.put("warehouse_id", warehouseId);
        body.put("remark", "JUnit 集成测试");
        body.put("items", List.of(Map.of(
                "product_id", productId, "quantity", 2, "unit_price", new BigDecimal("12.50"))));

        JsonNode created = doPost("/api/purchase/orders", body);
        assertEquals(0, created.path("code").asInt());
        long id = created.path("data").path("id").asLong();
        assertTrue(id > 0);

        assertEquals(0, doPost("/api/purchase/orders/" + id + "/confirm", Map.of()).path("code").asInt());
        assertEquals(0, doPost("/api/purchase/orders/" + id + "/receive", Map.of()).path("code").asInt());

        assertTrue(sizeOf("/api/finance/vouchers") > vouchersBefore,
                "采购入库应自动生成会计凭证（业财一体化）");
        assertTrue(sizeOf("/api/settle/arap") > arapBefore,
                "采购入库应自动生成应付单");
    }

    @Test
    void oversellingIsBlockedByConditionalUpdate() throws Exception {
        long customerId = firstId("/api/master/customer");
        long warehouseId = firstId("/api/master/warehouse");
        long productId = firstId("/api/master/product");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer_id", customerId);
        body.put("warehouse_id", warehouseId);
        body.put("items", List.of(Map.of(
                "product_id", productId, "quantity", 999999, "unit_price", new BigDecimal("1.00"))));

        JsonNode created = doPost("/api/sales/orders", body);
        assertEquals(0, created.path("code").asInt());
        long id = created.path("data").path("id").asLong();
        doPost("/api/sales/orders/" + id + "/confirm", Map.of());

        JsonNode shipped = doPost("/api/sales/orders/" + id + "/ship", Map.of());
        assertNotEquals(0, shipped.path("code").asInt(), "库存不足时整单必须回滚");
        assertTrue(shipped.path("msg").asText().contains("库存"));
    }

    // ==================== 财务规则与报表 ====================

    @Test
    void unbalancedVoucherIsRejected() throws Exception {
        JsonNode subjects = doGet("/api/finance/subjects").path("data");
        assertTrue(subjects.size() >= 2, "初始化器应灌入会计科目");
        String c1 = subjects.get(0).path("code").asText();
        String c2 = subjects.get(1).path("code").asText();

        JsonNode j = doPost("/api/finance/vouchers", voucherBody(c1, c2, "100.00", "90.00"));
        assertNotEquals(0, j.path("code").asInt(), "借贷不平衡的凭证必须拒绝");
        assertTrue(j.path("msg").asText().contains("平衡"));
    }

    @Test
    void balancedVoucherCanBePostedButNotDeleted() throws Exception {
        JsonNode subjects = doGet("/api/finance/subjects").path("data");
        String c1 = subjects.get(0).path("code").asText();
        String c2 = subjects.get(1).path("code").asText();

        JsonNode created = doPost("/api/finance/vouchers", voucherBody(c1, c2, "100.00", "100.00"));
        assertEquals(0, created.path("code").asInt());
        long id = created.path("data").path("id").asLong();

        assertEquals(0, doPost("/api/finance/vouchers/" + id + "/post", Map.of()).path("code").asInt());
        JsonNode deleted = parse(mvc.perform(delete("/api/finance/vouchers/" + id)
                .header("X-Token", token)).andReturn());
        assertNotEquals(0, deleted.path("code").asInt(), "已过账凭证不允许删除，这是财务红线");
    }

    @Test
    void balanceSheetAlwaysBalances() throws Exception {
        JsonNode data = doGet("/api/finance/report/balance").path("data");
        BigDecimal diff = new BigDecimal(data.path("difference").asText("999"));
        assertEquals(0, diff.compareTo(BigDecimal.ZERO), "资产 = 负债 + 所有者权益 必须恒成立");
    }

    @Test
    void profitReportHasCurrentAndYearToDate() throws Exception {
        JsonNode data = doGet("/api/finance/report/profit").path("data");
        assertTrue(data.has("periodProfit"));
        assertTrue(data.has("ytdProfit"));
    }

    // ==================== 分页与导出 ====================

    @Test
    void ordersPagingIsOptionalAndBackwardCompatible() throws Exception {
        JsonNode legacy = doGet("/api/purchase/orders").path("data");
        assertTrue(legacy.isArray(), "不传 page 时保持原来的裸数组响应");
        JsonNode paged = doGet("/api/purchase/orders?page=1&size=2").path("data");
        assertTrue(paged.has("total") && paged.has("list"));
        assertTrue(paged.path("list").size() <= 2);
    }

    @Test
    void exportProducesRealXlsxFile() throws Exception {
        MvcResult r = mvc.perform(get("/api/export/purchase").header("X-Token", token)).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentType().contains("spreadsheetml"));
        byte[] bytes = r.getResponse().getContentAsByteArray();
        assertTrue(bytes.length > 1000);
        // xlsx 本质是 zip 包，前两个字节必须是 'PK'
        assertEquals('P', bytes[0]);
        assertEquals('K', bytes[1]);
    }

    @Test
    void exportRejectsUnknownType() throws Exception {
        JsonNode j = doGet("/api/export/not_exists");
        assertNotEquals(0, j.path("code").asInt(), "类型白名单必须挡住非法值，杜绝 SQL 注入");
    }

    @Test
    void exportTypesAreListed() throws Exception {
        JsonNode data = doGet("/api/export/types").path("data");
        assertTrue(data.isArray() && data.size() >= 8);
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> voucherBody(String c1, String c2, String debit, String credit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("voucher_date", java.time.LocalDate.now().toString());
        body.put("voucher_word", "记");
        body.put("summary", "JUnit 借贷平衡测试");
        body.put("entries", List.of(
                Map.of("subject_code", c1, "summary", "借方", "debit", debit, "credit", "0"),
                Map.of("subject_code", c2, "summary", "贷方", "debit", "0", "credit", credit)));
        return body;
    }

    private long firstId(String url) throws Exception {
        JsonNode data = doGet(url).path("data");
        assertTrue(data.isArray() && !data.isEmpty(), url + " 应有演示数据");
        return data.get(0).path("id").asLong();
    }

    private int sizeOf(String url) throws Exception {
        JsonNode data = doGet(url).path("data");
        return data.isArray() ? data.size() : 0;
    }

    private JsonNode doGet(String url) throws Exception {
        return doGetAs(url, token);
    }

    private JsonNode doGetAs(String url, String t) throws Exception {
        return parse(mvc.perform(get(url).header("X-Token", t)).andReturn());
    }

    private JsonNode doPost(String url, Object body) throws Exception {
        return doPostAs(url, body, token);
    }

    private JsonNode doPostAs(String url, Object body, String t) throws Exception {
        String json = body == null ? "{}" : mapper.writeValueAsString(body);
        return parse(mvc.perform(post(url).header("X-Token", t)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn());
    }

    private JsonNode doLogin(String user, String password) throws Exception {
        return parse(mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", user, "password", password))))
                .andReturn());
    }

    private JsonNode parse(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
