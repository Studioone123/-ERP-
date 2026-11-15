package com.erp.excel;

import com.erp.common.BizException;
import com.erp.common.Db;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Excel 导出服务（Apache POI / xlsx）。
 *
 * <h3>为什么导出要单独做成服务</h3>
 * ERP 里几乎每个列表页都要「导出给财务/老板看」，如果每个 Controller 各写一套
 * 「查数据 → 拼表头 → 写 Workbook」，代码会迅速重复。这里把变化的部分抽成
 * {@link Def} 定义（一条 SQL + 一组「列名 → 中文表头」映射），生成逻辑只有一份。
 * 新增一种导出只需往 DEFS 里加一条，不用碰 POI 代码。
 *
 * <h3>安全设计</h3>
 * <ul>
 *   <li><b>类型白名单</b>：URL 里的 {@code type} 只当作 Map 的 key 查表，
 *       绝不拼接进 SQL。非法类型直接抛业务异常，从根上杜绝 SQL 注入。</li>
 *   <li><b>行数上限</b>：每条 SQL 都带 {@code LIMIT 50000}，避免一次导出把 JVM 堆打满。</li>
 *   <li><b>不接收自由筛选条件</b>：导出是「全量快照」，不接受前端拼的 where 片段。
 *       如果将来要按条件导出，应该用枚举化的参数 + 占位符绑定，而不是传 SQL 字符串。</li>
 * </ul>
 *
 * <h3>Excel 细节</h3>
 * 表头加粗蓝底、冻结首行、自动筛选、金额列保留两位小数的数字格式、
 * 日期列用真实 Date 单元格（Excel 里能排序），列宽按内容长度估算。
 * 这些看着琐碎，但决定了导出文件是「能直接交给财务用」还是「还要手工整理一遍」。
 */
@Service
public class ExportService {

    /** 单次导出的最大行数，防止误操作导致 OOM */
    private static final int MAX_ROWS = 50000;

    private final Db db;

    /** 类型 → 导出定义。LinkedHashMap 保证 /api/export/types 返回顺序稳定 */
    private final LinkedHashMap<String, Def> defs = new LinkedHashMap<>();

    public ExportService(Db db) {
        this.db = db;
        initDefs();
    }

    /** 供前端/接口文档列出可导出的报表 */
    public List<Map<String, Object>> types() {
        List<Map<String, Object>> list = new ArrayList<>();
        defs.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", k);
            m.put("name", v.title);
            m.put("columns", v.columns.size());
            list.add(m);
        });
        return list;
    }

    /** 生成 xlsx 字节流；type 不在白名单时抛业务异常 */
    public byte[] export(String type) {
        Def def = defs.get(type);
        if (def == null) {
            throw new BizException("不支持的导出类型：" + type + "，可用类型见 /api/export/types");
        }
        List<Map<String, Object>> rows = db.list(def.sql);

        // try-with-resources：Workbook 底层持有临时文件与流，不关闭会泄漏句柄
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("数据");
            CellStyle header = headerStyle(wb);
            CellStyle money = numberStyle(wb, "#,##0.00");
            CellStyle integer = numberStyle(wb, "0");
            CellStyle date = dateStyle(wb);

            List<String> keys = new ArrayList<>(def.columns.keySet());

            // ---- 表头 ----
            Row head = sheet.createRow(0);
            head.setHeightInPoints(22);
            for (int i = 0; i < keys.size(); i++) {
                Cell c = head.createCell(i);
                c.setCellValue(def.columns.get(keys.get(i)));
                c.setCellStyle(header);
                // 中文按两个字符宽度估算；限制在 10~40 之间，避免某一列撑满整屏
                int width = Math.max(10, Math.min(40, def.columns.get(keys.get(i)).length() * 2 + 4));
                sheet.setColumnWidth(i, width * 256);
            }

            // ---- 数据行 ----
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> data = rows.get(r);
                for (int i = 0; i < keys.size(); i++) {
                    String key = keys.get(i);
                    Object v = data.get(key);
                    Cell cell = row.createCell(i);
                    if (v == null) {
                        cell.setCellValue("");
                    } else if (v instanceof Date d) {
                        // java.sql.Timestamp / java.sql.Date 都继承 java.util.Date，
                        // 写成真实日期单元格，Excel 里可直接排序、按日期筛选
                        cell.setCellValue(d);
                        cell.setCellStyle(date);
                    } else if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                        cell.setCellStyle(isIntegerColumn(key) ? integer : money);
                    } else {
                        cell.setCellValue(display(key, String.valueOf(v)));
                    }
                }
            }

            // ---- 易用性：冻结首行 + 自动筛选 ----
            sheet.createFreezePane(0, 1);
            if (!keys.isEmpty()) {
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, keys.size() - 1));
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("生成 Excel 失败：" + e.getMessage());
        }
    }

    /** 文件名：中文标题 + 时间戳，避免浏览器下载时相互覆盖 */
    public String fileName(String type) {
        Def def = defs.get(type);
        String title = def == null ? "导出数据" : def.title;
        return title + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
    }

    /** 状态码 / 业务类型翻译成中文，让导出的文件不需要对照字典就能看懂 */
    private static String display(String column, String value) {
        if ("status".equals(column)) {
            return switch (value) {
                case "DRAFT" -> "草稿";
                case "CONFIRMED" -> "已审核";
                case "RECEIVED" -> "已入库";
                case "SHIPPED" -> "已发货";
                case "POSTED" -> "已过账";
                case "CANCELLED", "VOID" -> "已取消/作废";
                case "OPEN" -> "未结清";
                case "PARTIAL" -> "部分结清";
                case "CLOSED" -> "已结清";
                case "COMPLETED" -> "已完成";
                default -> value;
            };
        }
        if ("biz_type".equals(column)) {
            return switch (value) {
                case "PURCHASE_IN" -> "采购入库";
                case "SALE_OUT" -> "销售出库";
                case "ADJUST" -> "盘点调整";
                case "MATERIAL_ISSUE" -> "生产领料";
                case "PRODUCT_FINISH" -> "完工入库";
                default -> value;
            };
        }
        return value;
    }

    /** 数量类字段用整数格式，其余数字（金额、单价、余额）保留两位小数 */
    private static boolean isIntegerColumn(String key) {
        return key.endsWith("qty") || key.endsWith("quantity") || "safety_stock".equals(key)
                || "attachment_no".equals(key) || "item_count".equals(key);
    }

    private static CellStyle headerStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.WHITE.getIndex());
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private static CellStyle numberStyle(Workbook wb, String pattern) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat(pattern));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }

    private static CellStyle dateStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
        return style;
    }

    /** 一条导出定义：标题 + 查询 SQL + 列映射（英文列名 → 中文表头） */
    private static final class Def {
        private final String title;
        private final String sql;
        private final LinkedHashMap<String, String> columns;

        private Def(String title, String sql, String... pairs) {
            this.title = title;
            this.sql = sql;
            this.columns = new LinkedHashMap<>();
            for (int i = 0; i < pairs.length; i += 2) {
                this.columns.put(pairs[i], pairs[i + 1]);
            }
        }
    }

    /**
     * 集中登记所有可导出的报表。
     * SQL 里用 LEFT JOIN 把外键 id 换成名称 —— 直接把 id 导给业务人员是没有意义的。
     */
    private void initDefs() {
        defs.put("purchase", new Def("采购订单",
                "SELECT o.order_no, s.name AS supplier_name, w.name AS warehouse_name, "
                        + "o.total_amount, o.status, o.created_by, o.created_at "
                        + "FROM purchase_order o LEFT JOIN supplier s ON s.id=o.supplier_id "
                        + "LEFT JOIN warehouse w ON w.id=o.warehouse_id "
                        + "ORDER BY o.id DESC LIMIT " + MAX_ROWS,
                "order_no", "单据编号", "supplier_name", "供应商", "warehouse_name", "仓库",
                "total_amount", "订单金额", "status", "状态", "created_by", "制单人", "created_at", "创建时间"));

        defs.put("sales", new Def("销售订单",
                "SELECT o.order_no, c.name AS customer_name, w.name AS warehouse_name, "
                        + "o.total_amount, o.status, o.created_by, o.created_at "
                        + "FROM sales_order o LEFT JOIN customer c ON c.id=o.customer_id "
                        + "LEFT JOIN warehouse w ON w.id=o.warehouse_id "
                        + "ORDER BY o.id DESC LIMIT " + MAX_ROWS,
                "order_no", "单据编号", "customer_name", "客户", "warehouse_name", "仓库",
                "total_amount", "订单金额", "status", "状态", "created_by", "制单人", "created_at", "创建时间"));

        defs.put("stock", new Def("实时库存",
                "SELECT w.name AS warehouse_name, p.code AS product_code, p.name AS product_name, "
                        + "p.unit, i.quantity, p.cost_price, (i.quantity * p.cost_price) AS stock_value, "
                        + "p.safety_stock, i.updated_at "
                        + "FROM inventory i JOIN warehouse w ON w.id=i.warehouse_id "
                        + "JOIN product p ON p.id=i.product_id ORDER BY w.id, p.id LIMIT " + MAX_ROWS,
                "warehouse_name", "仓库", "product_code", "商品编码", "product_name", "商品名称",
                "unit", "单位", "quantity", "库存数量", "cost_price", "成本单价",
                "stock_value", "库存金额", "safety_stock", "安全库存", "updated_at", "更新时间"));

        defs.put("flows", new Def("库存流水",
                "SELECT f.flow_no, f.biz_type, f.biz_no, w.name AS warehouse_name, "
                        + "p.name AS product_name, f.change_qty, f.before_qty, f.after_qty, "
                        + "f.operator, f.remark, f.created_at "
                        + "FROM stock_flow f LEFT JOIN warehouse w ON w.id=f.warehouse_id "
                        + "LEFT JOIN product p ON p.id=f.product_id "
                        + "ORDER BY f.id DESC LIMIT " + MAX_ROWS,
                "flow_no", "流水号", "biz_type", "业务类型", "biz_no", "来源单号",
                "warehouse_name", "仓库", "product_name", "商品", "change_qty", "变动数量",
                "before_qty", "变动前", "after_qty", "变动后", "operator", "操作人",
                "remark", "备注", "created_at", "时间"));

        defs.put("vouchers", new Def("会计凭证",
                "SELECT voucher_no, voucher_date, account_period, voucher_word, summary, "
                        + "source_type, biz_no, status, total_debit, total_credit, created_by, created_at "
                        + "FROM fin_voucher ORDER BY id DESC LIMIT " + MAX_ROWS,
                "voucher_no", "凭证号", "voucher_date", "业务日期", "account_period", "会计期间",
                "voucher_word", "凭证字", "summary", "摘要", "source_type", "来源类型",
                "biz_no", "来源单号", "status", "状态", "total_debit", "借方合计",
                "total_credit", "贷方合计", "created_by", "制单人", "created_at", "创建时间"));

        defs.put("arap", new Def("应收应付",
                "SELECT bill_no, bill_type, partner_name, biz_no, amount, settled_amount, "
                        + "(amount - settled_amount) AS open_amount, status, bill_date, due_date "
                        + "FROM fin_ar_ap ORDER BY id DESC LIMIT " + MAX_ROWS,
                "bill_no", "往来单号", "bill_type", "类型", "partner_name", "往来单位",
                "biz_no", "来源单号", "amount", "单据金额", "settled_amount", "已核销",
                "open_amount", "未结金额", "status", "状态", "bill_date", "单据日期", "due_date", "到期日"));

        defs.put("products", new Def("商品档案",
                "SELECT code, name, category, spec, unit, cost_price, sale_price, "
                        + "safety_stock, status FROM product ORDER BY id LIMIT " + MAX_ROWS,
                "code", "商品编码", "name", "商品名称", "category", "分类", "spec", "规格",
                "unit", "单位", "cost_price", "成本价", "sale_price", "销售价",
                "safety_stock", "安全库存", "status", "状态"));

        defs.put("logs", new Def("操作日志",
                "SELECT username, action, detail, ip, created_at FROM sys_log "
                        + "ORDER BY id DESC LIMIT " + MAX_ROWS,
                "username", "操作人", "action", "操作", "detail", "详情", "ip", "IP", "created_at", "时间"));
    }
}
