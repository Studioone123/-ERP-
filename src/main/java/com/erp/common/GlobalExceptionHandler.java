package com.erp.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：把各种异常翻译成统一响应体 {@link R}，前端只需判断 code。
 *
 * <p>分层原则：
 * <ul>
 *   <li>业务规则被违反（库存不足、借贷不平、已过账不可删）→ BizException，返回可读提示；</li>
 *   <li>入参不合法（@Valid 校验失败、JSON 格式错误）→ 400 + 具体字段名，方便前端定位；</li>
 *   <li>未知异常 → 500，写完整堆栈到日志，但只回一句概要，不把 SQL / 类名泄露给调用方。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public R handleBiz(BizException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    /** @RequestBody 上的 @Valid 失败会抛这个异常 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R handleValid(MethodArgumentNotValidException e) {
        // fieldErrors 已经返回完整的 R（含字段级错误明细），不能再包一层 R.fail
        return fieldErrors(e.getBindingResult().getFieldErrors());
    }

    /** 表单/查询参数绑定校验失败 */
    @ExceptionHandler(BindException.class)
    public R handleBind(BindException e) {
        return fieldErrors(e.getBindingResult().getFieldErrors());
    }

    /** 类上的 @Validated 配合方法参数上的 @Min / @Pattern 等约束时抛这个 */
    @ExceptionHandler(ConstraintViolationException.class)
    public R handleConstraint(ConstraintViolationException e) {
        return R.fail(400, "参数校验失败：" + e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R handleUnreadable(HttpMessageNotReadableException e) {
        return R.fail(400, "请求体不是合法的 JSON，或字段类型不匹配");
    }

    @ExceptionHandler(Exception.class)
    public R handleOther(Exception e) {
        log.error("接口异常", e);
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return R.fail(500, "服务器异常：" + msg);
    }

    /**
     * 把所有字段错误汇总成一条消息（如「username 用户名不能为空；password 密码不能为空」）。
     * 同时把字段名放进 data，前端可以据此高亮对应输入框。
     */
    private static R fieldErrors(java.util.List<FieldError> errors) {
        StringBuilder sb = new StringBuilder("参数校验失败：");
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError f : errors) {
            if (sb.length() > 8) {
                sb.append("；");
            }
            sb.append(f.getField()).append(" ").append(f.getDefaultMessage());
            fields.put(f.getField(), f.getDefaultMessage());
        }
        return new R(400, sb.toString(), fields);
    }
}
