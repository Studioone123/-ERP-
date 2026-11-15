package com.erp.finance;

import com.erp.common.PageSupport;
import com.erp.common.R;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 财务总账 REST 接口。
 *
 * <p>接口按金蝶总账的操作动线组织：
 * 维护科目 → 录入凭证 → 审核过账 → 查科目余额表 / 明细账 → 出报表 → 期末结账。
 * Controller 只做「接参数、返结果」，所有业务规则都在 Service 层，
 * 这样同一套规则既服务于页面，也服务于将来可能的开放 API。
 */
@Tag(name = "财务总账", description = "会计科目、凭证、账簿、利润表 / 资产负债表、期末结账")
@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private final VoucherService voucherService;
    private final FinanceReportService reportService;

    public FinanceController(VoucherService voucherService, FinanceReportService reportService) {
        this.voucherService = voucherService;
        this.reportService = reportService;
    }

    // ---------------- 会计科目 ----------------

    /** 科目表：凭证录入页面的科目下拉数据源 */
    @GetMapping("/subjects")
    public R subjects() {
        return R.ok(voucherService.subjects());
    }

    @PostMapping("/subjects")
    public R createSubject(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("id", voucherService.createSubject(body)));
    }

    // ---------------- 凭证管理 ----------------

    /** 凭证列表：支持状态 / 期间 / 来源类型 / 关键词四个过滤条件 */
    @GetMapping("/vouchers")
    public R vouchers(@RequestParam(required = false) String status,
                      @RequestParam(required = false) String period,
                      @RequestParam(required = false) String keyword,
                      @RequestParam(required = false) String sourceType,
                      @RequestParam(required = false) Integer page,
                      @RequestParam(required = false) Integer size) {
        return PageSupport.reply(voucherService.list(status, period, keyword, sourceType), page, size);
    }

    @GetMapping("/vouchers/{id}")
    public R voucherDetail(@PathVariable long id) {
        return R.ok(voucherService.detail(id));
    }

    /** 手工录入凭证（草稿） */
    @PostMapping("/vouchers")
    public R createVoucher(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("id", voucherService.create(body)));
    }

    /** 审核过账：草稿 → 已过账，过账后不可修改 */
    @PostMapping("/vouchers/{id}/post")
    public R post(@PathVariable long id) {
        voucherService.post(id);
        return R.ok();
    }

    /** 删除草稿凭证 */
    @DeleteMapping("/vouchers/{id}")
    public R deleteVoucher(@PathVariable long id) {
        voucherService.delete(id);
        return R.ok();
    }

    // ---------------- 账簿 ----------------

    /** 科目余额表：期初 / 本期借贷 / 期末余额 */
    @GetMapping("/ledger/balance")
    public R subjectBalance(@RequestParam(required = false) String period) {
        return R.ok(reportService.subjectBalance(period));
    }

    /** 明细账：逐笔流水 + 滚动余额 */
    @GetMapping("/ledger/detail")
    public R detailLedger(@RequestParam String subjectCode,
                          @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to) {
        return R.ok(reportService.detailLedger(subjectCode, from, to));
    }

    // ---------------- 报表 ----------------

    /** 利润表：本期数 + 本年累计数 */
    @GetMapping("/report/profit")
    public R profit(@RequestParam(required = false) String period) {
        return R.ok(reportService.profit(period));
    }

    /** 资产负债表：资产 / 负债 / 权益 + 平衡校验差额 */
    @GetMapping("/report/balance")
    public R balance(@RequestParam(required = false) String period) {
        return R.ok(reportService.balance(period));
    }

    // ---------------- 期末结账 ----------------

    @GetMapping("/periods")
    public R periods() {
        return R.ok(reportService.periods());
    }

    /** body: {"period":"2026-11","reopen":false}；reopen=true 表示反结账 */
    @PostMapping("/periods/close")
    public R closePeriod(@RequestBody Map<String, Object> body) {
        String period = body.get("period") == null ? "" : String.valueOf(body.get("period")).trim();
        boolean reopen = Boolean.parseBoolean(String.valueOf(body.getOrDefault("reopen", false)));
        reportService.closePeriod(period, reopen);
        return R.ok();
    }
}
