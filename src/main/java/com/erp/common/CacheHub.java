package com.erp.common;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存操作的唯一入口。
 *
 * <p>缓存名集中定义为常量，避免各处手写字符串拼错（拼错的后果是「缓存永远不命中，
 * 也不失效」，非常隐蔽）。业务层用 {@code @Cacheable(cacheNames = CacheHub.XXX)}，
 * 失效由 {@link CacheEvictInterceptor} 调用这里的 {@link #evictAll()}。
 */
@Component
public class CacheHub {

    /** 经营看板整份 summary */
    public static final String DASHBOARD = "erp:dashboard";
    /** 商品/供应商/客户/仓库/部门/职员下拉选项 */
    public static final String OPTIONS = "erp:options";

    private final CacheManager cacheManager;

    public CacheHub(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** 清空所有 ERP 业务缓存。写操作成功后调用，简单且不会漏 */
    public void evictAll() {
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /** 运维页面展示当前缓存实现与缓存名 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("manager", cacheManager.getClass().getSimpleName());
        m.put("caches", cacheManager.getCacheNames());
        return m;
    }
}
