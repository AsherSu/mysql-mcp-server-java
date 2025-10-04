package cn.ashersu.mcp.mysql.service;

import cn.ashersu.mcp.mysql.audit.MysqlAuditService;
import cn.ashersu.mcp.mysql.exception.MysqlMcpException;
import cn.ashersu.mcp.mysql.manager.JdbcExecutor;
import cn.ashersu.mcp.mysql.manager.MysqlConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 支持多 MySQL 连接的工具，每个创建的连接分配一个 connectionId。
 * 后续执行 SQL 时携带该 connectionId 指定目标数据库。
 */
@Slf4j
@Service
public class MysqlToolService {

    /** 允许的非SELECT首关键字白名单 */
    private final Set<String> writeWhitelist = ConcurrentHashMap.newKeySet();

    /** 全局写操作开关，默认开启 */
    private final AtomicBoolean writeEnabled = new AtomicBoolean(false);

    // 结果集限制（可运行时动态调整）
    private volatile int maxQueryRows = 200;
    private volatile int maxFieldLength = 256;

    // 连接管理器
    private final MysqlConnectionManager mysqlConnectionManager;
    
    // 审计管理器
    private final MysqlAuditService mysqlAuditService;

    public MysqlToolService() {
        this.mysqlConnectionManager = new MysqlConnectionManager();
        this.mysqlAuditService = new MysqlAuditService(true);
    }

    // 修改构造函数以支持新的配置结构
    public MysqlToolService(@Value("${mcp.mysql.tools.nonQuery.enabled:false}") boolean writeEnabled,
                            @Value("${mcp.mysql.tools.nonQuery.whitelist:#{null}}") List<String> whitelist) {
        this.writeEnabled.set(writeEnabled);
        if (whitelist != null) {
            this.writeWhitelist.addAll(whitelist.stream()
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet()));
        }
        this.mysqlConnectionManager = new MysqlConnectionManager();
        this.mysqlAuditService = new MysqlAuditService(true);
    }

    // 保留旧的构造函数以保持向后兼容性
    public MysqlToolService(@Value("${mcp.mysql.write-enabled:false}") boolean writeEnabled) {
        this.writeEnabled.set(writeEnabled);
        this.mysqlConnectionManager = new MysqlConnectionManager();
        this.mysqlAuditService = new MysqlAuditService(true);
    }

    /**
     * 创建新的 MySQL 连接，返回分配的 connectionId。
     * example host: 127.0.0.1, port: 3306, params 可为空(如 useSSL=false&serverTimezone=UTC)
     */
    @Tool(description = "Create a new MySQL connection with advanced pool tuning (timeouts in ms). Return connectionId.")
    public Map<String,String> createConnectionAdvanced(String host,
                                                       Integer port,
                                                       String database,
                                                       String username,
                                                       String password,
                                                       String params,
                                                       Long connectionTimeoutMs,
                                                       Long idleTimeoutMs,
                                                       Long maxLifetimeMs) {
        return mysqlConnectionManager.createConnection(host, port, database, username, password, params,
                connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs);
    }

    @Tool(description = "Create a new MySQL connection. Return a generated connectionId. Subsequent queries must include this id.")
    public Map<String, String> createConnection(String host,
                                                Integer port,
                                                String database,
                                                String username,
                                                String password,
                                                String params) {
        return mysqlConnectionManager.createConnection(host, port, database, username, password, params,
                null,null,null);
    }

    @Tool(description = "List all active MySQL connectionIds and jdbc urls.")
    public List<Map<String, String>> listConnections() {
        return mysqlConnectionManager.listConnections();
    }

    @Tool(description = "Close and remove a MySQL connection by connectionId. Return true if removed.")
    public boolean closeConnection(String connectionId) {
        return mysqlConnectionManager.closeConnection(connectionId);
    }

    @Tool(description = "Execute a SELECT SQL on the specified connectionId and return rows (caution: large result sets truncated to 2000 chars JSON). Only SELECT allowed.")
    public List<Map<String, Object>> queryWithConnection(String connectionId, String sql) {
        validateSelect(sql);
        return JdbcExecutor.queryForList(mysqlConnectionManager.getDataSource(connectionId), sql, maxQueryRows, maxFieldLength);
    }

    @Tool(description = "List all table names (comma separated) for the given connectionId.")
    public String listAllTablesName(String connectionId) {
        List<Map<String, Object>> tableNames = JdbcExecutor.queryForList(mysqlConnectionManager.getDataSource(connectionId),
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", maxQueryRows, maxFieldLength);
        return tableNames.stream()
                .map(e -> e.values().iterator().next().toString())
                .collect(Collectors.joining(","));
    }

    @Tool(description = "Execute a non-SELECT (INSERT/UPDATE/DELETE/DDL) SQL if its first keyword is in whitelist. Returns affected rows.")
    public int executeUpdateWithConnection(String connectionId, String sql) {
        if (!writeEnabled.get()) {
            throw new MysqlMcpException("Write operations disabled");
        }
        String first = firstKeyword(sql);
        if (!writeWhitelist.contains(first)) {
            throw new MysqlMcpException("SQL verb not allowed: " + first);
        }
        long start = System.currentTimeMillis();
        try (var c = mysqlConnectionManager.getDataSource(connectionId).getConnection();
             var ps = c.prepareStatement(sql)) {
            int affected = ps.executeUpdate();
            long duration = System.currentTimeMillis() - start;
            mysqlAuditService.addAudit(connectionId, first, duration, affected);
            return affected;
        } catch (java.sql.SQLException e) {
            throw new MysqlMcpException("Update failed: " + e.getMessage());
        }
    }

    @Tool(description = "Enable non-SELECT(write/DDL) operations globally. Return true if state changed.")
    public boolean enableWriteOperations() { return !writeEnabled.getAndSet(true); }

    @Tool(description = "Disable non-SELECT(write/DDL) operations globally. Return true if state changed.")
    public boolean disableWriteOperations() { return writeEnabled.getAndSet(false); }

    @Tool(description = "Return whether non-SELECT(write/DDL) operations are currently enabled.")
    public boolean isWriteEnabled() { return writeEnabled.get(); }

    @Tool(description = "List current non-SELECT whitelist keywords.")
    public List<String> listWriteWhitelist() {
        return writeWhitelist.stream().sorted().toList();
    }

    @Tool(description = "Add a keyword to non-SELECT whitelist. Return true if added.")
    public boolean addAllowedWriteCommand(String keyword) {
        return writeWhitelist.add(keyword.toLowerCase(Locale.ROOT));
    }

    @Tool(description = "Remove a keyword from whitelist. Return true if removed.")
    public boolean removeAllowedWriteCommand(String keyword) {
        return writeWhitelist.remove(keyword.toLowerCase(Locale.ROOT));
    }

    @Tool(description = "Get table schema (column_name, data_type, is_nullable, column_default) for a table on the given connectionId.")
    public List<Map<String, Object>> getTableSchema(String connectionId, String tableName) {
        String sql = "SELECT column_name AS COLUMN_NAME,data_type AS DATA_TYPE,is_nullable AS IS_NULLABLE,column_default AS COLUMN_DEFAULT FROM information_schema.columns WHERE table_name = '" + tableName + "' AND table_schema = DATABASE()";
        return JdbcExecutor.queryForList(mysqlConnectionManager.getDataSource(connectionId), sql, maxQueryRows, maxFieldLength);
    }

    @Tool(description = "Set maximum rows returned by SELECT queries; returns previous value.")
    public int setMaxQueryRows(int rows) {
        if (rows <= 0) throw new MysqlMcpException("rows must be > 0");
        int prev = this.maxQueryRows;
        this.maxQueryRows = rows;
        return prev;
    }

    @Tool(description = "Set maximum field (string) length per cell; returns previous value. Longer values will be truncated with a suffix.")
    public int setMaxFieldLength(int length) {
        if (length <= 0) throw new MysqlMcpException("length must be > 0");
        int prev = this.maxFieldLength;
        this.maxFieldLength = length;
        return prev;
    }

    @Tool(description = "Get current max query rows & field length limits.")
    public Map<String,Integer> getResultLimitConfig() {
        return Map.of("maxQueryRows", maxQueryRows, "maxFieldLength", maxFieldLength);
    }

    @Tool(description = "Return write audit entries (most recent first) limited by 'limit'. Each entry contains: timestamp, connectionId, verb, durationMs, affectedRows.")
    public List<Map<String,Object>> listWriteAudit(int limit) {
        return mysqlAuditService.listWriteAudit(limit);
    }

    @Tool(description = "Clear all write audit entries; returns number cleared.")
    public int clearWriteAudit() {
        return mysqlAuditService.clearWriteAudit();
    }

    private void validateSelect(String sql) {
        if (sql == null || !sql.trim().toLowerCase(Locale.ROOT).startsWith("select")) {
            throw new MysqlMcpException("Only SELECT statements allowed.");
        }
    }

    private String firstKeyword(String sql) {
        if (sql == null) return "";
        String[] parts = sql.trim().split("\\s+",2);
        return parts.length==0?"":parts[0].toLowerCase(Locale.ROOT);
    }
}