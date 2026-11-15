package com.erp.manufacture;

import com.erp.common.R;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 生产制造 REST 接口（金蝶「生产数据管理 + 生产任务管理」）。
 * BOM 维护与生产任务单分两条路径，动作动词与单据状态一一对应。
 */
@RestController
@RequestMapping("/api/mf")
public class ManufactureController {

    private final BomService bomService;
    private final ManufactureService manufactureService;

    public ManufactureController(BomService bomService, ManufactureService manufactureService) {
        this.bomService = bomService;
        this.manufactureService = manufactureService;
    }

    // ---------------- BOM ----------------

    @GetMapping("/boms")
    public R boms(@RequestParam(required = false) String keyword) {
        return R.ok(bomService.list(keyword));
    }

    @GetMapping("/boms/{id}")
    public R bomDetail(@PathVariable long id) {
        return R.ok(bomService.detail(id));
    }

    @PostMapping("/boms")
    public R createBom(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("id", bomService.create(body)));
    }

    @DeleteMapping("/boms/{id}")
    public R deleteBom(@PathVariable long id) {
        bomService.delete(id);
        return R.ok();
    }

    // ---------------- 生产任务 ----------------

    @GetMapping("/orders")
    public R orders(@RequestParam(required = false) String status,
                    @RequestParam(required = false) String keyword) {
        return R.ok(manufactureService.list(status, keyword));
    }

    @GetMapping("/orders/{id}")
    public R orderDetail(@PathVariable long id) {
        return R.ok(manufactureService.detail(id));
    }

    @PostMapping("/orders")
    public R createOrder(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("id", manufactureService.create(body)));
    }

    @PostMapping("/orders/{id}/confirm")
    public R confirm(@PathVariable long id) {
        manufactureService.confirm(id);
        return R.ok();
    }

    @PostMapping("/orders/{id}/issue")
    public R issue(@PathVariable long id) {
        manufactureService.issue(id);
        return R.ok();
    }

    @PostMapping("/orders/{id}/finish")
    public R finish(@PathVariable long id) {
        manufactureService.finish(id);
        return R.ok();
    }

    @PostMapping("/orders/{id}/cancel")
    public R cancel(@PathVariable long id) {
        manufactureService.cancel(id);
        return R.ok();
    }
}
