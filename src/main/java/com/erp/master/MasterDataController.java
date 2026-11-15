package com.erp.master;

import com.erp.common.R;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/master")
public class MasterDataController {

    private final MasterDataService service;

    public MasterDataController(MasterDataService service) {
        this.service = service;
    }

    /** 下拉框数据：仓库 / 供应商 / 客户 / 商品 */
    @GetMapping("/options")
    public R options() {
        return R.ok(service.options());
    }

    @GetMapping("/{type}")
    public R list(@PathVariable String type,
                  @RequestParam(required = false) String keyword,
                  @RequestParam(required = false) String status) {
        return R.ok(service.list(type, keyword, status));
    }

    @GetMapping("/{type}/{id}")
    public R get(@PathVariable String type, @PathVariable long id) {
        return R.ok(service.get(type, id));
    }

    @PostMapping("/{type}")
    public R create(@PathVariable String type, @RequestBody Map<String, Object> body) {
        return R.ok(Map.of("id", service.create(type, body)));
    }

    @PutMapping("/{type}/{id}")
    public R update(@PathVariable String type, @PathVariable long id, @RequestBody Map<String, Object> body) {
        service.update(type, id, body);
        return R.ok();
    }

    @DeleteMapping("/{type}/{id}")
    public R delete(@PathVariable String type, @PathVariable long id) {
        service.delete(type, id);
        return R.ok();
    }
}
