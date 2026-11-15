package com.erp.settle;

import com.erp.common.R;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 往来管理 REST 接口（金蝶「应收应付」模块的对外入口）。
 * 页面动线：往来单据 → 账龄分析 → 收款 / 付款核销 → 核销记录。
 */
@RestController
@RequestMapping("/api/settle")
public class SettleController {

    private final ArApService service;

    public SettleController(ArApService service) {
        this.service = service;
    }

    /** 往来单列表：type=AR/AP，status=OPEN/PARTIAL/CLOSED */
    @GetMapping("/arap")
    public R arap(@RequestParam(required = false) String type,
                  @RequestParam(required = false) String status,
                  @RequestParam(required = false) String keyword) {
        return R.ok(service.list(type, status, keyword));
    }

    /** 顶部汇总指标 */
    @GetMapping("/summary")
    public R summary() {
        return R.ok(service.summary());
    }

    /** 账龄分析 */
    @GetMapping("/aging")
    public R aging(@RequestParam(required = false) String type) {
        return R.ok(service.aging(type));
    }

    /** 收付款核销记录 */
    @GetMapping("/records")
    public R records(@RequestParam(required = false) String keyword) {
        return R.ok(service.records(keyword));
    }

    /** 执行收款 / 付款核销（同时生成会计凭证） */
    @PostMapping("/settle")
    public R settle(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("id", service.settle(body)));
    }
}
