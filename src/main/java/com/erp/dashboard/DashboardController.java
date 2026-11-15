package com.erp.dashboard;

import com.erp.common.Db;
import com.erp.common.PageSupport;
import com.erp.common.R;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "经营看板与日志", description = "首页汇总指标与系统操作日志")
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService dashboardService;
    private final Db db;

    public DashboardController(DashboardService dashboardService, Db db) {
        this.dashboardService = dashboardService;
        this.db = db;
    }

    @GetMapping("/dashboard/summary")
    public R summary() {
        return R.ok(dashboardService.summary());
    }

    /**
     * 操作日志。这里是全项目唯一一处<b>数据库分页</b>的示范：
     * 日志表会持续增长，用 COUNT(*) + LIMIT/OFFSET 只取当前页，
     * 不像其他列表那样把全部结果捞回 JVM 再切页。
     * 不传 page 时仍返回原来的「最近 200 条」，兼容既有前端。
     */
    @GetMapping("/logs")
    public R logs(@RequestParam(required = false) String keyword,
                  @RequestParam(required = false) Integer page,
                  @RequestParam(required = false) Integer size) {
        boolean hasKw = keyword != null && !keyword.isBlank();
        String sql = "SELECT * FROM sys_log ";
        Object[] args;
        if (hasKw) {
            String kw = "%" + keyword.trim() + "%";
            sql += "WHERE username LIKE ? OR action LIKE ? OR detail LIKE ? ";
            args = new Object[]{kw, kw, kw};
        } else {
            args = new Object[0];
        }
        sql += "ORDER BY id DESC";
        if (page == null) {
            return R.ok(db.list(sql + " LIMIT 200", args));
        }
        return R.ok(PageSupport.query(db, sql, args, page, size));
    }
}
