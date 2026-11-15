package com.erp.ai;

import com.erp.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 经营分析接口。
 *
 * <p>权限归属：挂在公共模块（见 {@code RolePermission.COMMON}），与看板同级——
 * 分析所依据的数据全部来自看板级汇总，不涉及任何模块的敏感明细，
 * 因此所有登录角色都可用；写操作权限模型不受影响。
 */
@Tag(name = "AI 经营分析", description = "基于实时业务数据的智能分析报告（LLM + 本地规则引擎双模式）")
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiAnalysisService service;

    public AiController(AiAnalysisService service) {
        this.service = service;
    }

    @Operation(summary = "AI 引擎状态", description = "返回当前生效的分析引擎（大模型 / 本地规则）与可用分析类型")
    @GetMapping("/status")
    public R status() {
        return R.ok(service.status());
    }

    @Operation(summary = "生成经营分析报告",
            description = "type=overview 经营总览 / sales 销售 / inventory 库存 / finance 财务。"
                    + "返回 source 字段标识本次由大模型还是本地规则引擎生成；结果带缓存，任何业务写操作后自动重新生成")
    @GetMapping("/analyze")
    public R analyze(@Parameter(description = "分析类型", example = "overview")
                     @RequestParam(defaultValue = "overview") String type) {
        return R.ok(service.analyze(type));
    }
}
