package com.example;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 支持多 MySQL 连接的工具，每个创建的连接分配一个 connectionId。
 * 后续执行 SQL 时携带该 connectionId 指定目标数据库。
 */
@Slf4j
@Service
public class MultiConnectionSqlTool {

    /** connectionId -> DataSource */
    private final Map<String, HikariDataSource> dataSourceMap = new ConcurrentHashMap<>();

    /** 允许的非SELECT首关键字白名单 */
    private final Set<String> writeWhitelist = ConcurrentHashMap.newKeySet();

    /** 全局写操作开关，默认开启 */
    private final AtomicBoolean writeEnabled = new AtomicBoolean(false);

    // 结果集限制（可运行时动态调整）
    private volatile int maxQueryRows = 200;
    private volatile int maxFieldLength = 256;

    // 写操作审计日志
    private static final int MAX_AUDIT_ENTRIES = 1000;
    private final Deque<WriteAuditEntry> writeAudit = new ArrayDeque<>();


    public MultiConnectionSqlTool() {
    }

    public MultiConnectionSqlTool(@Value("${mcp.mysql.write-enabled:false}") boolean writeEnabled) {
        this.writeEnabled.set(writeEnabled);
    }

    {
        // 初始化默认白名单
        writeWhitelist.addAll(List.of("insert","update","delete","create","drop","alter","truncate","replace"));
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
        return createConnectionInternal(host, port, database, username, password, params,
                connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs);
    }

    @Tool(description = "Create a new MySQL connection. Return a generated connectionId. Subsequent queries must include this id.")
    public Map<String, String> createConnection(String host,
                                                Integer port,
                                                String database,
                                                String username,
                                                String password,
                                                String params) {
        return createConnectionInternal(host, port, database, username, password, params,
                null,null,null);
    }

    private Map<String,String> createConnectionInternal(String host,
                                                        Integer port,
                                                        String database,
                                                        String username,
                                                        String password,
                                                        String params,
                                                        Long connectionTimeoutMs,
                                                        Long idleTimeoutMs,
                                                        Long maxLifetimeMs) {
        // 原实现迁移
        Objects.requireNonNull(host, "host required");
        Objects.requireNonNull(port, "port required");
        Objects.requireNonNull(database, "database required");
        String connectionId = UUID.randomUUID().toString();
        String paramSegment = (params == null || params.isBlank()) ? "useSSL=false&serverTimezone=UTC&characterEncoding=utf8" : params.trim();
        String jdbcUrl = String.format(Locale.ROOT,
                "jdbc:mysql://%s:%d/%s?%s", host, port, database, paramSegment);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setPoolName("mcpmysql-" + connectionId.substring(0, 8));
        // 超时策略，若未指定使用默认值
        if (connectionTimeoutMs != null) config.setConnectionTimeout(connectionTimeoutMs);
        else config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10));
        if (idleTimeoutMs != null) config.setIdleTimeout(idleTimeoutMs);
        else config.setIdleTimeout(TimeUnit.MINUTES.toMillis(5));
        if (maxLifetimeMs != null) config.setMaxLifetime(maxLifetimeMs);
        else config.setMaxLifetime(TimeUnit.MINUTES.toMillis(30));
        config.setValidationTimeout(TimeUnit.SECONDS.toMillis(5));
        HikariDataSource ds = new HikariDataSource(config);
        // 先测试连接
        try (Connection c = ds.getConnection()) {
            // 简单校验
            c.prepareStatement("SELECT 1").execute();
        } catch (SQLException e) {
            ds.close();
            throw new MysqlMcpException("Cannot establish connection: " + e.getMessage());
        }
        dataSourceMap.put(connectionId, ds);
        log.info("Created MySQL connection {} => {}", connectionId, jdbcUrl);
        Map<String, String> ret = new LinkedHashMap<>();
        ret.put("connectionId", connectionId);
        ret.put("url", jdbcUrl);
        return ret;
    }

    @Tool(description = "List all active MySQL connectionIds and jdbc urls.")
    public List<Map<String, String>> listConnections() {
        return dataSourceMap.entrySet().stream()
                .map(e -> Map.of("connectionId", e.getKey(), "url", e.getValue().getJdbcUrl()))
                .collect(Collectors.toList());
    }

    @Tool(description = "Close and remove a MySQL connection by connectionId. Return true if removed.")
    public boolean closeConnection(String connectionId) {
        HikariDataSource ds = dataSourceMap.remove(connectionId);
        if (ds != null) {
            ds.close();
            return true;
        }
        return false;
    }

    @Tool(description = "Execute a SELECT SQL on the specified connectionId and return rows (caution: large result sets truncated to 2000 chars JSON). Only SELECT allowed.")
    public List<Map<String, Object>> queryWithConnection(String connectionId, String sql) {
        HikariDataSource ds = requireDataSource(connectionId);
        validateSelect(sql);
        return JdbcExecutor.queryForList(ds, sql, maxQueryRows, maxFieldLength);
    }

    @Tool(description = "List all table names (comma separated) for the given connectionId.")
    public String listAllTablesName(String connectionId) {
        HikariDataSource ds = requireDataSource(connectionId);
        List<Map<String, Object>> tableNames = JdbcExecutor.queryForList(ds,
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
        HikariDataSource ds = requireDataSource(connectionId);
        String first = firstKeyword(sql);
        if (!writeWhitelist.contains(first)) {
            throw new MysqlMcpException("SQL verb not allowed: " + first);
        }
        long start = System.currentTimeMillis();
        try (Connection c = ds.getConnection(); var ps = c.prepareStatement(sql)) {
            int affected = ps.executeUpdate();
            long duration = System.currentTimeMillis() - start;
            addAudit(connectionId, first, duration, affected);
            log.info("AUDIT write connectionId={} verb={} affectedRows={} durationMs={}", connectionId, first, affected, duration);
            return affected;
        } catch (SQLException e) {
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
        HikariDataSource ds = requireDataSource(connectionId);
        String sql = "SELECT column_name AS COLUMN_NAME,data_type AS DATA_TYPE,is_nullable AS IS_NULLABLE,column_default AS COLUMN_DEFAULT FROM information_schema.columns WHERE table_name = '" + tableName + "' AND table_schema = DATABASE()";
        return JdbcExecutor.queryForList(ds, sql, maxQueryRows, maxFieldLength);
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
        if (limit <= 0) return List.of();
        List<Map<String,Object>> list = new ArrayList<>();
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

    @Tool(description = "Clear all write audit entries; returns number cleared.")
    public int clearWriteAudit() {
        synchronized (writeAudit) {
            int size = writeAudit.size();
            writeAudit.clear();
            return size;
        }
    }

    private HikariDataSource requireDataSource(String connectionId) {
        HikariDataSource ds = dataSourceMap.get(connectionId);
        if (ds == null) {
            throw new MysqlMcpException("Unknown connectionId: " + connectionId);
        }
        return ds;
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

    private void addAudit(String connectionId, String verb, long durationMs, int affectedRows) {
        synchronized (writeAudit) {
            writeAudit.addLast(new WriteAuditEntry(connectionId, verb, durationMs, affectedRows, System.currentTimeMillis()));
            while (writeAudit.size() > MAX_AUDIT_ENTRIES) {
                writeAudit.removeFirst();
            }
        }
    }

    /**
     * 简单执行器，避免为每个动态数据源创建 Spring JdbcTemplate Bean。
     */
    static class JdbcExecutor {
        static List<Map<String, Object>> queryForList(DataSource ds, String sql, int maxRows, int maxFieldLength) {
            try (Connection c = ds.getConnection();
                 var ps = c.prepareStatement(sql);
                 var rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int colCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    if (rows.size() >= maxRows) break; // 行数截断
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        if (val instanceof String s && s.length() > maxFieldLength) {
                            String truncated = s.substring(0, Math.min(s.length(), maxFieldLength)) + "...(truncated,len=" + s.length() + ")";
                            row.put(rs.getMetaData().getColumnLabel(i), truncated);
                        } else {
                            row.put(rs.getMetaData().getColumnLabel(i), val);
                        }
                    }
                    rows.add(row);
                }
                return rows;
            } catch (SQLException e) {
                throw new MysqlMcpException("Query failed: " + e.getMessage());
            }
        }
    }

    private record WriteAuditEntry(String connectionId, String verb, long durationMs, int affectedRows, long epochMillis) {}
}
