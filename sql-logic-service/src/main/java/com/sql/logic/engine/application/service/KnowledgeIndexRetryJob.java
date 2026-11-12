package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.infrastructure.dao.BusinessKnowledgeDao;
import com.sql.logic.engine.infrastructure.po.BusinessKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/** Retries durable pending embedding and deletion work after transient PG/API failures. */
@Component
public class KnowledgeIndexRetryJob {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexRetryJob.class);
    private final BusinessKnowledgeDao dao;
    private final BusinessKnowledgeAppService service;

    public KnowledgeIndexRetryJob(BusinessKnowledgeDao dao, BusinessKnowledgeAppService service) {
        this.dao = dao;
        this.service = service;
    }

    @Scheduled(initialDelayString = "${app.rag.retry-delay-ms:60000}",
            fixedDelayString = "${app.rag.retry-delay-ms:60000}")
    public void retry() {
        // Leave in-flight writes a minute to complete. The row lock serializes retries across instances.
        Date cutoff = new Date(System.currentTimeMillis() - 60_000);
        var rows = dao.selectList(new QueryWrapper<BusinessKnowledge>()
                .in("status", 0, 2).lt("update_time", cutoff)
                .orderByAsc("update_time").last("LIMIT 100"));
        for (BusinessKnowledge row : rows) {
            try { service.retryPending(row.getId()); }
            catch (Exception e) { log.warn("Knowledge retry failed for {}: {}", row.getId(), e.getMessage()); }
        }
    }
}
