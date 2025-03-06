package com.example;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlTool {

    private final JdbcTemplate jdbcTemplate;

    @Tool(description = "Execute a select SQL query and return results in a readable format. Results will be truncated after 4000 characters.")
    public List<Map<String, Object>> query(String sql) {
        if (!sql.toLowerCase().startsWith("select")) {
            throw new SqlGenerationException("Only SELECT queries are allowed.");
        }
        return jdbcTemplate.queryForList(sql);
    }

    @Tool(description = "Return all table names in the database separated by comma.")
    public String listAllTablesName() {
        List<Map<String, Object>> tableNames = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()");
        return tableNames.stream()
                .map(e -> e.values().iterator().next().toString())
                .collect(Collectors.joining(","));
    }

    @Tool(description = "Returns schema and relation information for the given table. Includes column name, data type and constraints.")
    public String getTableSchema(String tableName) {
        String sql = """
                SELECT
                    column_name,
                    data_type,
                    is_nullable,
                    column_default
                FROM information_schema.columns
                WHERE table_name = ?""";
        List<Map<String, Object>> columnNames = jdbcTemplate
                .queryForList(sql, tableName);
        return columnNames.stream()
                .map(e -> e.values().iterator().next().toString())
                .collect(Collectors.joining(","));
    }

}
