package com.erp.manufacture;

import com.erp.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * 物料清单 BOM 维护 —— 对应金蝶 K/3「生产数据管理 → BOM 维护」。
 *
 * <p>BOM（Bill of Materials）描述「生产一个成品需要消耗哪些子件、各多少」。
 * 例如生产 1 把办公椅 = 1 个椅面网布组件 + 1 个五爪底座组件。
 * 生产任务单领料时按 BOM 展开，自动计算应领数量，避免车间随意领料。
 *
 * <p>本系统采用<b>单层 BOM</b>（成品 → 子件），金蝶支持多层（半成品再展开），
 * 多层需要递归展开算法，代码导读文档中给出了扩展思路。
 */
@Service
public class BomService {

    private final Db db;
    private final NoGenerator noGenerator;
    private final LogService logService;

    public BomService(Db db, NoGenerator noGenerator, LogService logService) {
        this.db = db;
        this.noGenerator = noGenerator;
        this.logService = logService;
    }

    /** BOM 列表，带成品名称与子件数量 */
    public List<Map<String, Object>> list(String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT b.*, p.code AS product_code, p.name AS product_name, p.unit, "
                        + "(SELECT COUNT(*) FROM mf_bom_item i WHERE i.bom_id=b.id) AS item_count "
                        + "FROM mf_bom b LEFT JOIN product p ON p.id=b.product_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (b.bom_code LIKE ? OR p.name LIKE ? OR p.code LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            Collections.addAll(args, kw, kw, kw);
        }
        sql.append(" ORDER BY b.id DESC");
        return db.list(sql.toString(), args.toArray());
    }

    /** BOM 详情：主表 + 子件明细（带子件编码、名称、单位、成本价，便于前端展示与算料） */
    public Map<String, Object> detail(long id) {
        Map<String, Object> bom = require(id);
        Map<String, Object> result = new LinkedHashMap<>(bom);
        result.put("items", db.list(
                "SELECT i.*, p.code AS material_code, p.name AS material_name, "
                        + "p.unit, p.cost_price FROM mf_bom_item i "
                        + "LEFT JOIN product p ON p.id=i.material_id WHERE i.bom_id=? ORDER BY i.id", id));
        return result;
    }

    /**
     * 新建 BOM。
     * 校验：成品必须是已启用商品、至少一个子件、子件不能是成品自己（防止循环用料）、
     * 用量与损耗率必须合法。
     */
    @Transactional
    public long create(Map<String, Object> body) {
        long productId = Db.lng(body, "product_id", 0);
        if (productId <= 0 || db.one("SELECT id FROM product WHERE id=? AND status=1", productId) == null) {
            throw new BizException("请选择有效的产成品");
        }
        List<Map<String, Object>> items = parseItems(body, productId);
        String code = noGenerator.bom();
        long bomId = db.insert("INSERT INTO mf_bom(bom_code,product_id,version,name,status) VALUES(?,?,?,?,1)",
                code, productId, defaultString(Db.str(body, "version"), "V1.0"),
                defaultString(Db.str(body, "name"), "标准 BOM"));
        for (Map<String, Object> item : items) {
            db.exec("INSERT INTO mf_bom_item(bom_id,material_id,quantity,loss_rate) VALUES(?,?,?,?)",
                    bomId, Db.lng(item, "material_id", 0), Db.integer(item, "quantity", 0),
                    Db.decimal(item, "loss_rate"));
        }
        logService.record("新增 BOM", code + "，共 " + items.size() + " 个子件");
        return bomId;
    }

    /** 删除 BOM：已被生产任务引用则禁止删除 */
    @Transactional
    public void delete(long id) {
        Map<String, Object> bom = require(id);
        Integer refs = db.value("SELECT COUNT(*) FROM mf_order WHERE bom_id=?", Integer.class, id);
        if (refs != null && refs > 0) {
            throw new BizException("该 BOM 已被生产任务引用，不能删除");
        }
        db.exec("DELETE FROM mf_bom_item WHERE bom_id=?", id);
        db.exec("DELETE FROM mf_bom WHERE id=?", id);
        logService.record("删除 BOM", Val.strOf(bom.get("bom_code")));
    }

    private List<Map<String, Object>> parseItems(Map<String, Object> body, long productId) {
        Object raw = body.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new BizException("请至少添加一个 BOM 子件");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new BizException("子件格式不正确");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) m;
            long materialId = Db.lng(item, "material_id", 0);
            int qty = Db.integer(item, "quantity", 0);
            BigDecimal loss = Db.decimal(item, "loss_rate");
            if (materialId <= 0 || qty <= 0) {
                throw new BizException("请选择子件且用量必须大于 0");
            }
            if (materialId == productId) {
                throw new BizException("子件不能是产成品自身");
            }
            if (!seen.add(materialId)) {
                throw new BizException("同一个子件不能重复添加，请合并用量");
            }
            if (loss.signum() < 0 || loss.compareTo(new BigDecimal("100")) > 0) {
                throw new BizException("损耗率应在 0 ~ 100 之间");
            }
            if (db.one("SELECT id FROM product WHERE id=? AND status=1", materialId) == null) {
                throw new BizException("子件物料不存在或已停用");
            }
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> require(long id) {
        Map<String, Object> row = db.one("SELECT * FROM mf_bom WHERE id=?", id);
        if (row == null) {
            throw new BizException("BOM 不存在");
        }
        return row;
    }

    private static String defaultString(String v, String fallback) {
        return v.isEmpty() ? fallback : v;
    }
}
