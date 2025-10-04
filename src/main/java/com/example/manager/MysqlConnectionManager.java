package com.example.manager;

import com.example.exception.MysqlMcpException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MySQL连接管理器，负责创建、维护和关闭数据库连接
 */
@Slf4j
public class MysqlConnectionManager {
    
    /** connectionId -> DataSource */
    private final Map<String, HikariDataSource> dataSourceMap = new ConcurrentHashMap<>();
    
    /**
     * 创建新的 MySQL 连接，返回分配的 connectionId。
     * example host: 127.0.0.1, port: 3306, params 可为空(如 useSSL=false&serverTimezone=UTC)
     */
    public Map<String, String> createConnection(String host,
                                                Integer port,
                                                String database,
                                                String username,
                                                String password,
                                                String params,
                                                Long connectionTimeoutMs,
                                                Long idleTimeoutMs,
                                                Long maxLifetimeMs) {
        Objects.requireNonNull(host, "host required");
        Objects.requireNonNull(port, "port required");
        Objects.requireNonNull(database, "database required");
        String connectionId = UUID.randomUUID().toString();
        String paramSegment = (params == null || params.isBlank()) ? "useSSL=false&serverTimezone=UTC&characterEncoding=utf8" : params.trim();
        String jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?%s", host, port, database, paramSegment);
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
    
    /**
     * 获取指定连接ID的数据源
     *
     * @param connectionId 连接ID
     * @return 对应的数据源
     * @throws MysqlMcpException 如果连接ID不存在
     */
    public HikariDataSource getDataSource(String connectionId) {
        HikariDataSource ds = dataSourceMap.get(connectionId);
        if (ds == null) {
            throw new MysqlMcpException("Unknown connectionId: " + connectionId);
        }
        return ds;
    }
    
    /**
     * 列出所有活动的连接
     *
     * @return 连接ID和URL的映射列表
     */
    public java.util.List<Map<String, String>> listConnections() {
        return dataSourceMap.entrySet().stream()
                .map(e -> Map.of("connectionId", e.getKey(), "url", e.getValue().getJdbcUrl()))
                .collect(Collectors.toList());
    }
    
    /**
     * 关闭并移除指定连接
     *
     * @param connectionId 连接ID
     * @return 如果成功移除返回true，否则返回false
     */
    public boolean closeConnection(String connectionId) {
        HikariDataSource ds = dataSourceMap.remove(connectionId);
        if (ds != null) {
            ds.close();
            return true;
        }
        return false;
    }
    
    /**
     * 关闭所有连接
     */
    public void closeAllConnections() {
        dataSourceMap.values().forEach(HikariDataSource::close);
        dataSourceMap.clear();
    }
}