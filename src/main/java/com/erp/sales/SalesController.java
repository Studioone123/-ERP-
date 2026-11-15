package com.erp.sales;

import com.erp.common.PageSupport;
import com.erp.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "销售管理", description = "销售订单：草稿 → 审核 → 发货出库 → 完成，出库校验库存防超卖")
@RestController
@RequestMapping("/api/sales")
public class SalesController {

    private final SalesService service;

    public SalesController(SalesService service) {
        this.service = service;
    }

    @Operation(summary = "销售订单列表", description = "支持状态、关键词过滤与可选分页")
    @GetMapping("/orders")
    public R list(@RequestParam(required = false) String status,
                  @RequestParam(required = false) String keyword,
                  @RequestParam(required = false) Integer page,
                  @RequestParam(required = false) Integer size) {
        return PageSupport.reply(service.list(status, keyword), page, size);
    }

    @GetMapping("/orders/{id}")
    public R detail(@PathVariable long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/orders")
    public R create(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("id", service.create(body)));
    }

    @PostMapping("/orders/{id}/confirm")
    public R confirm(@PathVariable long id) {
        service.confirm(id);
        return R.ok();
    }

    @PostMapping("/orders/{id}/ship")
    public R ship(@PathVariable long id) {
        service.ship(id);
        return R.ok();
    }

    @PostMapping("/orders/{id}/cancel")
    public R cancel(@PathVariable long id) {
        service.cancel(id);
        return R.ok();
    }
}
