package com.sql.logic.engine.infrastructure.aop;

import com.sql.logic.engine.infrastructure.dao.SqlAuditLogDao;
import com.sql.logic.engine.infrastructure.po.SqlAuditLog;
import com.sql.logic.engine.trigger.http.dto.SqlConsoleExecuteRequest;
import com.sql.logic.engine.trigger.http.dto.SqlConsoleExecuteResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Date;

@Aspect
@Component
public class SqlAuditLogAspect {

    private final SqlAuditLogDao sqlAuditLogDao;

    public SqlAuditLogAspect(SqlAuditLogDao sqlAuditLogDao) {
        this.sqlAuditLogDao = sqlAuditLogDao;
    }

    @Around("@annotation(com.sql.logic.engine.infrastructure.annotation.RecordSqlAudit)")
    public Object recordAuditLog(ProceedingJoinPoint joinPoint) throws Throwable {
        Object result = null;
        Throwable error = null;
        
        try {
            result = joinPoint.proceed();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                // Try to find the SqlConsoleExecuteRequest in args
                SqlConsoleExecuteRequest request = null;
                for (Object arg : joinPoint.getArgs()) {
                    if (arg instanceof SqlConsoleExecuteRequest) {
                        request = (SqlConsoleExecuteRequest) arg;
                        break;
                    }
                }

                if (request != null) {
                    SqlAuditLog log = new SqlAuditLog();
                    log.setUserId(request.getUserId());
                    log.setConnectionId(request.getConnectionId());
                    log.setSqlScript(request.getSql());
                    log.setCreateTime(new Date());

                    if (error != null) {
                        log.setStatus("FAILED");
                        log.setErrorMessage(error.getMessage());
                    } else if (result instanceof SqlConsoleExecuteResponse) {
                        SqlConsoleExecuteResponse response = (SqlConsoleExecuteResponse) result;
                        log.setStatus(response.isSuccess() ? "SUCCESS" : "FAILED");
                        log.setExecuteLatency(response.getLatency());
                        log.setErrorMessage(response.getErrorMessage());
                    } else {
                        log.setStatus("SUCCESS");
                    }

                    sqlAuditLogDao.insert(log);
                }
            } catch (Exception e) {
                // Do not interrupt the normal flow if logging fails
            }
        }
        
        return result;
    }
}