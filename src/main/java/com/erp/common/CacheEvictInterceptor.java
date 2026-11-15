package com.erp.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 统一的缓存失效拦截器。
 *
 * <p><b>设计取舍</b>：缓存失效最容易出错的地方不是「怎么写」，而是「漏写」——
 * 采购、销售、库存、盘点、生产、凭证、基础资料……每个模块的每个写方法都加一行
 * {@code @CacheEvict}，只要漏一个，看板就会长期显示旧数据。
 *
 * <p>这里改用一个零侵入的做法：拦截所有成功返回（无异常、HTTP < 400）的
 * POST / PUT / DELETE / PATCH 请求，直接清空全部业务缓存。
 * 粒度粗一点，但绝对不会漏；ERP 的写操作频率远低于读操作，多清几次缓存的代价可以忽略。
 *
 * <p>执行时机在 {@code afterCompletion}，此时 Service 层的 {@code @Transactional}
 * 已经提交完毕，不会出现「缓存清了但事务回滚了」导致的不一致。
 */
@Component
public class CacheEvictInterceptor implements HandlerInterceptor {

    private final CacheHub cacheHub;

    public CacheEvictInterceptor(CacheHub cacheHub) {
        this.cacheHub = cacheHub;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (ex != null || response.getStatus() >= 400) {
            return; // 失败的请求没有改变数据，不需要失效缓存
        }
        String method = request.getMethod();
        if ("POST".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PATCH".equals(method)) {
            cacheHub.evictAll();
        }
    }
}
