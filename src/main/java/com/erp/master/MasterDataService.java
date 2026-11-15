package com.erp.master;

import com.erp.common.BizException;
import com.erp.common.CacheHub;
import com.erp.common.Db;
import com.erp.common.LogService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 基础资料通用 CRUD：商品 / 供应商 / 客户 / 仓库。
 * 通过白名单元数据限制表名与可写字段，杜绝 SQL 注入。
 */
@Service
public class MasterDataService {

    private record Meta(String table, String label, String[] fields, String[] searchCols, String refSql) {
    }

    private static final Map<String, Meta> METAS;

    static {
        Map<String, Meta> m = new LinkedHashMap<>();
        m.put("product", new Meta("product", "商品",
                new String[]{"code", "name", "category", "spec", "unit", "cost_price", "sale_price", "safety_stock", "status"},
                new String[]{"code", "name", "category"},
                "SELECT (SELECT COUNT(*) FROM inventory WHERE product_id=?) "
                        + "+ (SELECT COUNT(*) FROM purchase_item WHERE product_id=?) "
                        + "+ (SELECT COUNT(*) FROM sales_item WHERE product_id=?)"));
        m.put("supplier", new Meta("supplier", "供应商",
                new String[]{"code", "name", "contact", "phone", "address", "status"},
                new String[]{"code", "name", "contact", "phone"},
                "SELECT COUNT(*) FROM purchase_order WHERE supplier_id=?"));
        m.put("customer", new Meta("customer", "客户",
                new String[]{"code", "name", "contact", "phone", "address", "status"},
                new String[]{"code", "name", "contact", "phone"},
                "SELECT COUNT(*) FROM sales_order WHERE customer_id=?"));
        m.put("warehouse", new Meta("warehouse", "仓库",
                new String[]{"code", "name", "address", "status"},
                new String[]{"code", "name", "address"},
                "SELECT COUNT(*) FROM inventory WHERE warehouse_id=?"));
        // 借鉴金蝶「组织与人资基础资料」扩展的部门 / 职员。
        // 这两张表没有被业务单据以外键方式引用，删除检查用常量 0 即可
        // （delete() 会按 SQL 中 ? 的个数生成参数，0 个 ? 就传空数组）。
        m.put("department", new Meta("department", "部门",
                new String[]{"code", "name", "manager", "phone", "status"},
                new String[]{"code", "name", "manager"},
                "SELECT 0"));
        m.put("employee", new Meta("employee", "职员",
                new String[]{"code", "name", "department", "position", "phone", "status"},
                new String[]{"code", "name", "department", "position", "phone"},
                "SELECT 0"));
        METAS = Collections.unmodifiableMap(m);
    }

    private final Db db;
    private final LogService logService;

    public MasterDataService(Db db, LogService logService) {
        this.db = db;
        this.logService = logService;
    }

    private Meta meta(String type) {
        Meta meta = METAS.get(type);
        if (meta == null) {
            throw new BizException("未知的资料类型：" + type);
        }
        return meta;
    }

    public List<Map<String, Object>> list(String type, String keyword, String status) {
        Meta meta = meta(type);
        StringBuilder sql = new StringBuilder("SELECT * FROM " + meta.table() + " WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (");
            for (int i = 0; i < meta.searchCols().length; i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append(meta.searchCols()[i]).append(" LIKE ?");
                args.add("%" + keyword.trim() + "%");
            }
            sql.append(")");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(Integer.parseInt(status));
        }
        sql.append(" ORDER BY id DESC LIMIT 500");
        return db.list(sql.toString(), args.toArray());
    }

    public Map<String, Object> get(String type, long id) {
        Meta meta = meta(type);
        Map<String, Object> row = db.one("SELECT * FROM " + meta.table() + " WHERE id=?", id);
        if (row == null) {
            throw new BizException(meta.label() + "不存在");
        }
        return row;
    }

    @Transactional
    public long create(String type, Map<String, Object> body) {
        Meta meta = meta(type);
        validate(meta, body, true);
        String code = Db.str(body, "code");
        Integer exists = db.value("SELECT COUNT(*) FROM " + meta.table() + " WHERE code=?", Integer.class, code);
        if (exists != null && exists > 0) {
            throw new BizException("编码 " + code + " 已存在");
        }
        StringJoiner cols = new StringJoiner(",");
        StringJoiner marks = new StringJoiner(",");
        List<Object> args = new ArrayList<>();
        for (String field : meta.fields()) {
            cols.add(field);
            marks.add("?");
            args.add(value(body, field));
        }
        long id = db.insert("INSERT INTO " + meta.table() + "(" + cols + ") VALUES(" + marks + ")", args.toArray());
        logService.record("新增" + meta.label(), "编码 " + code + " / " + Db.str(body, "name"));
        return id;
    }

    @Transactional
    public void update(String type, long id, Map<String, Object> body) {
        Meta meta = meta(type);
        get(type, id);
        validate(meta, body, false);
        String code = Db.str(body, "code");
        if (!code.isEmpty()) {
            Integer exists = db.value("SELECT COUNT(*) FROM " + meta.table() + " WHERE code=? AND id<>?",
                    Integer.class, code, id);
            if (exists != null && exists > 0) {
                throw new BizException("编码 " + code + " 已被其它记录使用");
            }
        }
        StringJoiner sets = new StringJoiner(",");
        List<Object> args = new ArrayList<>();
        for (String field : meta.fields()) {
            if (body.containsKey(field)) {
                sets.add(field + "=?");
                args.add(value(body, field));
            }
        }
        if (sets.length() == 0) {
            throw new BizException("没有需要更新的字段");
        }
        args.add(id);
        db.exec("UPDATE " + meta.table() + " SET " + sets + " WHERE id=?", args.toArray());
        logService.record("修改" + meta.label(), "ID " + id + " / " + Db.str(body, "name"));
    }

    @Transactional
    public void delete(String type, long id) {
        Meta meta = meta(type);
        get(type, id);
        Integer refs = db.value(meta.refSql(), Integer.class, repeat(id, meta.refSql().length() - meta.refSql().replace("?", "").length()));
        if (refs != null && refs > 0) {
            throw new BizException("该" + meta.label() + "已被库存或业务单据引用，不能删除；可将状态改为「停用」");
        }
        db.exec("DELETE FROM " + meta.table() + " WHERE id=?", id);
        logService.record("删除" + meta.label(), "ID " + id);
    }

    private static Object[] repeat(long id, int times) {
        Object[] args = new Object[times];
        Arrays.fill(args, id);
        return args;
    }

    /**
     * 下拉框数据：仓库 / 供应商 / 客户 / 商品 / 部门 / 职员。
     *
     * <p><b>优化点</b>：这个接口在前端几乎每打开一个单据弹窗都会调用一次，
     * 每次要跑 6 条查询，而基础资料变更频率很低。加 {@code @Cacheable} 后
     * 只有首次（或写操作后失效）才回源。失效由 CacheEvictInterceptor 统一处理，
     * 所以这里的 create/update/delete 不需要各自写 {@code @CacheEvict}。
     */
    @Cacheable(cacheNames = CacheHub.OPTIONS, key = "'all'")
    public Map<String, Object> options() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("warehouses", db.list("SELECT id, code, name FROM warehouse WHERE status=1 ORDER BY id"));
        result.put("suppliers", db.list("SELECT id, code, name FROM supplier WHERE status=1 ORDER BY id"));
        result.put("customers", db.list("SELECT id, code, name FROM customer WHERE status=1 ORDER BY id"));
        result.put("products", db.list("SELECT id, code, name, unit, cost_price, sale_price, safety_stock FROM product WHERE status=1 ORDER BY id"));
        // 扩展基础资料：部门 / 职员下拉，供职员档案与生产任务选负责人使用
        result.put("departments", db.list("SELECT id, code, name FROM department WHERE status=1 ORDER BY id"));
        result.put("employees", db.list("SELECT id, code, name, department FROM employee WHERE status=1 ORDER BY id"));
        return result;
    }

    private void validate(Meta meta, Map<String, Object> body, boolean create) {
        if (create) {
            if (Db.str(body, "code").isEmpty()) {
                throw new BizException(meta.label() + "编码不能为空");
            }
            if (Db.str(body, "name").isEmpty()) {
                throw new BizException(meta.label() + "名称不能为空");
            }
        }
        if (body.containsKey("status")) {
            int status = Db.integer(body, "status", 1);
            if (status != 0 && status != 1) {
                throw new BizException("状态只能是 0 或 1");
            }
        }
        if ("product".equals(meta.table())) {
            if (Db.decimal(body, "cost_price").signum() < 0 || Db.decimal(body, "sale_price").signum() < 0) {
                throw new BizException("价格不能为负数");
            }
            if (Db.integer(body, "safety_stock", 0) < 0) {
                throw new BizException("安全库存不能为负数");
            }
        }
    }

    private Object value(Map<String, Object> body, String field) {
        return switch (field) {
            case "cost_price", "sale_price" -> Db.decimal(body, field);
            case "safety_stock" -> Db.integer(body, field, 0);
            case "status" -> Db.integer(body, field, 1);
            default -> Db.str(body, field);
        };
    }
}
