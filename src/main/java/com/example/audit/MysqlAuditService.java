package com.example.audit;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 写操作审计日志管理器
 */
@Slf4j
public class MysqlAuditService {
    
    // 写操作审计日志
    private static final int MAX_AUDIT_ENTRIES = 1000;
    private final Deque<WriteAuditEntry> writeAudit = new ArrayDeque<>();
    private final boolean auditEnabled;
    
    public MysqlAuditService(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }
    
    /**
     * 添加审计条目
     *
     * @param connectionId 连接ID
     * @param verb         SQL动词
     * @param durationMs   执行时长（毫秒）
     * @param affectedRows 影响行数
     */
    public void addAudit(String connectionId, String verb, long durationMs, int affectedRows) {
        if (!auditEnabled) {
            return;
        }
        
        synchronized (writeAudit) {
            writeAudit.addLast(new WriteAuditEntry(connectionId, verb, durationMs, affectedRows, System.currentTimeMillis()));
            while (writeAudit.size() > MAX_AUDIT_ENTRIES) {
                writeAudit.removeFirst();
            }
        }
        log.info("AUDIT write connectionId={} verb={} affectedRows={} durationMs={}", connectionId, verb, affectedRows, durationMs);
    }
    
    /**
     * 获取写操作审计日志
     *
     * @param limit 限制条数
     * @return 审计日志列表
     */
    public List<Map<String, Object>> listWriteAudit(int limit) {
        if (limit <= 0 || !auditEnabled) return List.of();
        List<Map<String, Object>> list = new ArrayList<>();
        synchronized (writeAudit) {
            int count = 0;
            for (var it = writeAudit.descendingIterator(); it.hasNext() && count < limit; count++) {
                var e = it.next();
                list.add(Map.of(
                        "timestamp", e.epochMillis(),
                        "connectionId", e.connectionId(),
                        "verb", e.verb(),
                        "durationMs", e.durationMs(),
                        "affectedRows", e.affectedRows()
                ));
            }
        }
        return list;
    }
    
    /**
     * 清除所有审计日志
     *
     * @return 清除的条目数
     */
    public int clearWriteAudit() {
        if (!auditEnabled) return 0;
        synchronized (writeAudit) {
            int size = writeAudit.size();
            writeAudit.clear();
            return size;
        }
    }
    
    private record WriteAuditEntry(String connectionId, String verb, long durationMs, int affectedRows, long epochMillis) {
    }
}