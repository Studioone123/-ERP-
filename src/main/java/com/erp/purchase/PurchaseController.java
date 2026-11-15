package com.erp.purchase;

import com.erp.common.PageSupport;
import com.erp.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "采购管理", description = "采购订单：草稿 → 审核 → 收货入库 → 完成")
@RestController
@RequestMapping("/api/purchase")
public class PurchaseController {

    private final PurchaseService service;

    public PurchaseController(PurchaseService service) {
        this.service = service;
    }

    /**
     * 订单列表。page 不传时返回原有裸数组（兼容现有前端），
     * 传了 page/size 时返回 PageResult（list/total/page/size/pages）。
     */
    @Operation(summary = "采购订单列表", description = "支持状态、关键词过滤与可选分页")
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

    @PostMapping("/orders/{id}/receive")
    public R receive(@PathVariable long id) {
        service.receive(id);
        return R.ok();
    }

    @PostMapping("/orders/{id}/cancel")
    public R cancel(@PathVariable long id) {
        service.cancel(id);
        return R.ok();
    }
}
