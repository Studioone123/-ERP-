package com.erp.common;

import java.util.List;

/**
 * 统一分页响应体。
 *
 * <p>字段命名与主流前端分页组件对齐：{@code list / total / page / size / pages}。
 * 之所以不直接返回裸数组，是因为前端要渲染「共 N 条、第 x/y 页」时还需要总数；
 * 把总数和当前页一起返回，比让前端再发一次 count 请求更省一次往返。
 *
 * @param <T> 列表元素类型，这里大多是 {@code Map<String,Object>}（JdbcTemplate 的行映射结果）
 */
public class PageResult<T> {

    private final List<T> list;
    private final long total;
    private final int page;
    private final int size;
    private final int pages;

    public PageResult(List<T> list, long total, int page, int size) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
        // 向上取整：(total + size - 1) / size，避免 total=21、size=20 时算出 1 页
        this.pages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
    }

    public List<T> getList() {
        return list;
    }

    public long getTotal() {
        return total;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public int getPages() {
        return pages;
    }
}
