package com.erp.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 操作日志；写日志失败不影响主业务 */
@Service
public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);
    private final Db db;

    public LogService(Db db) {
        this.db = db;
    }

    public void record(String action, String detail) {
        try {
            String user = UserContext.get();
            db.exec("INSERT INTO sys_log(username, action, detail, ip) VALUES(?,?,?,?)",
                    user == null ? "system" : user, action,
                    detail == null ? "" : (detail.length() > 480 ? detail.substring(0, 480) : detail),
                    "WEB");
        } catch (Exception e) {
            log.warn("写操作日志失败: {}", e.getMessage());
        }
    }
}
