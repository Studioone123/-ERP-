package com.erp.inventory;

import com.erp.common.PageSupport;
import com.erp.common.R;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "库存管理", description = "实时库存、库存流水与盘点调整")
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/stock")
    public R stock(@RequestParam(required = false) String warehouseId,
                   @RequestParam(required = false) String keyword,
                   @RequestParam(required = false, defaultValue = "false") boolean alertOnly,
                   @RequestParam(required = false) Integer page,
                   @RequestParam(required = false) Integer size) {
        return PageSupport.reply(service.stock(warehouseId, keyword, alertOnly), page, size);
    }

    @GetMapping("/flows")
    public R flows(@RequestParam(required = false) String bizType,
                   @RequestParam(required = false) String keyword,
                   @RequestParam(required = false) Integer page,
                   @RequestParam(required = false) Integer size) {
        return PageSupport.reply(service.flows(bizType, keyword), page, size);
    }

    @PostMapping("/adjust")
    public R adjust(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("quantity", service.adjust(body)));
    }
}
