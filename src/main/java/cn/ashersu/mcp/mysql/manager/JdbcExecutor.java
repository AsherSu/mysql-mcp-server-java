package cn.ashersu.mcp.mysql.manager;

import cn.ashersu.mcp.mysql.exception.MysqlMcpException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简单执行器，避免为每个动态数据源创建 Spring JdbcTemplate Bean。
 */
public class JdbcExecutor {
    
    public static List<Map<String, Object>> queryForList(DataSource ds, String sql, int maxRows, int maxFieldLength) {
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